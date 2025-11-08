package org.koitharu.kotatsu.core.network.webview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.browser.BrowserCallback
import org.koitharu.kotatsu.core.network.webview.adblock.AdBlock
import org.koitharu.kotatsu.core.util.ext.configureForParser
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val TAG_VRF = "MF_VRF"

@Singleton
class WebViewRequestInterceptorExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adBlock: AdBlock?,
) {

    private var webViewCached: WeakReference<WebView>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun interceptRequests(
        url: String,
        config: InterceptionConfig
    ): List<InterceptedRequest> = withTimeout(config.timeoutMs + 5000) {
        Log.d(TAG_VRF, "interceptRequests start url=$url injectPageScript=${!config.pageScript.isNullOrBlank()} hasFilterScript=${!config.filterScript.isNullOrBlank()}")
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val resultDeferred = CompletableDeferred<List<InterceptedRequest>>()

                val interceptor = object : WebViewRequestInterceptor {
                    override fun shouldCaptureRequest(request: InterceptedRequest): Boolean {
                        val urlOk = config.urlPattern?.containsMatchIn(request.url) ?: true
                        val scriptOk = try {
                            if (config.filterScript.isNullOrBlank()) true
                            else evaluateFilterPredicate(config.filterScript, request.url)
                        } catch (e: Throwable) {
                            Log.w(TAG_VRF, "Filter error ${e.message}")
                            false
                        }
                        val match = urlOk && scriptOk
                        Log.v(TAG_VRF, "REQ url=${request.url} method=${request.method} urlOk=$urlOk scriptOk=$scriptOk match=$match")
                        return match
                    }

                    override fun onInterceptionComplete(capturedRequests: List<InterceptedRequest>) {
                        Log.d(TAG_VRF, "Interception complete captured=${capturedRequests.size}")
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.complete(capturedRequests)
                        }
                    }

                    override fun onInterceptionError(error: Throwable) {
                        Log.w(TAG_VRF, "Interception error", error)
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.completeExceptionally(error)
                        }
                    }
                }

                val callback = object : BrowserCallback {
                    override fun onLoadingStateChanged(isLoading: Boolean) {
                        Log.v(TAG_VRF, "Loading state changed isLoading=$isLoading")
                    }
                    override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) {
                        Log.v(TAG_VRF, "Title changed title=$title subtitle=$subtitle")
                    }
                    override fun onHistoryChanged() {
                        Log.v(TAG_VRF, "History changed")
                    }
                }

                try {
                    val webView = obtainWebView()
                    val client = RequestInterceptorWebViewClient(callback, adBlock, config, interceptor)
                    webView.webViewClient = client
                    webView.loadUrl(url)

                    val timeoutRunnable = Runnable {
                        Log.w(TAG_VRF, "Timeout, stopping capture")
                        client.stopCapturing()
                    }
                    mainHandler.postDelayed(timeoutRunnable, config.timeoutMs)

                    resultDeferred.invokeOnCompletion { ex ->
                        mainHandler.removeCallbacks(timeoutRunnable)
                        if (ex != null) continuation.resumeWithException(ex)
                        else continuation.resume(resultDeferred.getCompleted())
                    }
                    continuation.invokeOnCancellation {
                        client.stopCapturing()
                        mainHandler.removeCallbacks(timeoutRunnable)
                        if (!resultDeferred.isCompleted) resultDeferred.cancel()
                    }
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    suspend fun captureWebViewUrls(
        pageUrl: String,
        urlPattern: Regex,
        timeout: Long = 30000L
    ): List<String> {
        val config = InterceptionConfig(
            timeoutMs = timeout,
            urlPattern = urlPattern,
            maxRequests = 50
        )
        return interceptRequests(pageUrl, config)
            .also { Log.d(TAG_VRF, "captureWebViewUrls matched=${it.size}") }
            .map { it.url }
    }

    suspend fun extractVrfToken(
        pageUrl: String,
        timeout: Long = 15000L
    ): String? {
        val vrfPattern = Regex("/ajax/read/.*[?&]vrf=([^&]+)")
        val config = InterceptionConfig(
            timeoutMs = timeout,
            urlPattern = vrfPattern,
            maxRequests = 10
        )
        val requests = interceptRequests(pageUrl, config)
        val vrf = requests.firstOrNull()?.getQueryParameter("vrf")
        Log.d(TAG_VRF, "extractVrfToken result=$vrf")
        return vrf
    }

    @MainThread
    private fun obtainWebView(): WebView {
        webViewCached?.get()?.let { return it }
        val wv = WebView(context).apply { configureForParser(null) }
        Log.d(TAG_VRF, "Created new WebView instance")
        webViewCached = WeakReference(wv)
        return wv
    }
}

// If you added evaluateFilterPredicate earlier, keep it here.
fun evaluateFilterPredicate(script: String, requestUrl: String): Boolean {
    val returnIdx = script.lastIndexOf("return")
    if (returnIdx == -1) {
        Log.v(TAG_VRF, "No return in script, capturing all. url=$requestUrl")
        return true
    }
    val expr = script.substring(returnIdx + 6).substringBefore(";").trim()
    if (expr.isEmpty()) {
        Log.v(TAG_VRF, "Empty return expression, capturing all. url=$requestUrl")
        return true
    }
    val orClauses = expr.split("||").map { it.trim() }
    for (clause in orClauses) {
        val andTerms = clause.trim().trim('(', ')')
            .split("&&").map { it.trim() }.filter { it.isNotEmpty() }
        var allMatch = true
        for (term in andTerms) {
            val m = Regex("""url\.includes\(\s*(['"])(.*?)\1\s*\)""").find(term)
            if (m != null) {
                val needle = m.groupValues[2]
                val contains = requestUrl.contains(needle)
                if (!contains) {
                    allMatch = false
                    break
                }
            } else {
                allMatch = false
                break
            }
        }
        if (allMatch) {
            Log.v(TAG_VRF, "Predicate MATCH url=$requestUrl clause=$clause")
            return true
        }
    }
    Log.v(TAG_VRF, "Predicate MISS url=$requestUrl expr=$expr")
    return false
}
