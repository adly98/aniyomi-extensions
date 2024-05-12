package eu.kanade.tachiyomi.animeextension.ar.cimalek.interceptor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import eu.kanade.tachiyomi.network.POST
import okhttp3.Headers.Companion.toHeaders
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GetSourcesInterceptor(private val client: OkHttpClient) : Interceptor {
    private val context = Injekt.get<Application>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val initWebView by lazy {
        WebSettings.getDefaultUserAgent(context)
    }

    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        initWebView

        val request = chain.request()

        try {
            val newRequest = resolveWithWebView(request)

            return chain.proceed(newRequest ?: request)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(request: Request): Request? {
        val latch = CountDownLatch(1)

        var webView: WebView? = null

        val origRequestUrl = request.url.toString()
        val headers = request.headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()
        var newRequest: Request? = null

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:94.0) Gecko/20100101 Firefox/94.0"
            }
            webview.webViewClient = object : WriteHandlingWebViewClient(webview) {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WriteHandlingWebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    val types = Regex("""action\d.php""")
                    if (types.containsMatchIn(url)) {
                        val newHeaders = request.requestHeaders.toHeaders()
                        val newBody = request.ajaxData.toRequestBody()
                        newRequest = POST(url, newHeaders, newBody)
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView?.loadUrl(origRequestUrl, headers)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
        return newRequest
    }

    companion object {
        const val TIMEOUT_SEC: Long = 20
    }
}
