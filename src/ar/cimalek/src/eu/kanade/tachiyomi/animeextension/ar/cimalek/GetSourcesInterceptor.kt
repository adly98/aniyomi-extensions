package eu.kanade.tachiyomi.animeextension.ar.cimalek

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.POST
import okhttp3.FormBody
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GetSourcesInterceptor(private val getSources: String, private val client: OkHttpClient) : Interceptor {
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
            webview.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    if (url.contains(getSources)) {
                        val newHeaders = request.requestHeaders.toHeaders()
                        val newBody = FormBody.Builder()
                            .add("tsvbvo", "MmRVYWJ2aW12cUZRTGw3UTZSTDRPQk5vbVNmSmh1VW9NZVk5SUZmZUhvck1TV3VidWFOYkxucTR6K25HZFRPUFJ6ZGtpQjBQd2FYQzZyRHJJU0hycWI4cXluQXBCZVljQ2xFSzZ4SGxHV2x1U3QxTEtieFQxRkdna2JwZlVsTkFlL2N2WTdkbjNxaGI5Z3Y2Zlp3cVVrTEFMUXk3Y2FtajZxRGtvSVgxTE1La1NHMEdOQTMrOWdtQXBDZktENkUzR0c1Qm43dVZmb0pNU3BCSE9ZdkpkWjRuLzF4b1FmTENoYW5QWHdTYy9jd09uSm9vK3dwbTNmbmcrZ3p6d2dpeTJ0ZHBHZFZVNzFqci9tSDRkNlRmNUJQY2tsMUJDN0tIZnMxTWE3M01DS2dvWitGN3E1Ty9YSW5DWXk2RU5vK3VmM2haLzZKWlhtTExEOHRlS0Jsd295aG5Pd2lNTDVxL2J0cUNrTXJVYk5zRk1ERmVpeVlzcHo0R2hUV2I3NXl4b2JrRDBhQk9lTko3ejY4VXlucDN4RlM1TG55Ukd0T0JkQ0pKcnRnUzZKa085dkMyTHFTWFAyK0k5TlBDZmhWc2tsMTVxb0N0TjRXV1ppK3VVSzhSOGoyb2xhbVNFWnl3dHlVRVFXVkljN3VWbmxTekpkWHhNTXI5VWZXeURUWGhySGczWmtyME5EZGw3dXhNT2w0YnQ1MjhjZEZvcmxxT2JqNm1LT00xa1hjPQ==")
                            .build()
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
