package com.example.waferai.data.local

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.waferai.data.remote.WaferDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal object WaferParquet {
    suspend fun parse(context: Context, bytes: ByteArray, filename: String): ParsedWafer {
        require(bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("PAR1".toByteArray()) &&
            bytes.copyOfRange(bytes.size - 4, bytes.size).contentEquals("PAR1".toByteArray())) {
            "该文件不是可读取的 Parquet 文件。"
        }
        val json = runScript(context, "reader.js", "readWafer", bytes)
        require(json.isNotBlank()) { "无法解析 Parquet。请核对五列坐标结构、重复坐标及文件大小。" }
        return WaferJson.parse(json.toByteArray(), filename)
    }

    suspend fun write(context: Context, wafer: WaferDetail): ByteArray {
        val encoded = runScript(context, "writer.js", "writeWafer", WaferFileWriter.json(wafer).toByteArray())
        require(encoded.isNotBlank() && encoded.length <= 12 * 1024 * 1024) { "无法生成 Parquet 文件。" }
        return Base64.decode(encoded, Base64.NO_WRAP).also {
            require(it.size in 12..WaferTestData.MAX_BYTES) { "生成的 Parquet 文件超出大小限制。" }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runScript(context: Context, file: String, function: String, bytes: ByteArray): String {
        val script = withContext(Dispatchers.IO) {
            context.assets.open("parquet/$file").bufferedReader().use { it.readText() }
        }
        val call = "$function('${Base64.encodeToString(bytes, Base64.NO_WRAP)}')"
        return withContext(Dispatchers.Main) {
            val view = WebView(context)
            try {
                withTimeoutOrNull(20_000) {
                    suspendCancellableCoroutine { continuation ->
                        view.settings.apply {
                            javaScriptEnabled = true
                            blockNetworkLoads = true
                            allowFileAccess = false
                            allowContentAccess = false
                        }
                        val bridge = ResultBridge { result ->
                            if (continuation.isActive) continuation.resume(result)
                        }
                        view.addJavascriptInterface(bridge, "WaferResult")
                        view.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                if (continuation.isActive) view.evaluateJavascript(call, null)
                            }
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                bridge.complete("")
                                return true
                            }
                        }
                        view.loadDataWithBaseURL("https://waferai.invalid/", "<script>$script</script>", "text/html", "UTF-8", null)
                    }
                }.orEmpty()
            } finally {
                view.removeJavascriptInterface("WaferResult")
                view.destroy()
            }
        }
    }

    private class ResultBridge(private val callback: (String) -> Unit) {
        private val delivered = java.util.concurrent.atomic.AtomicBoolean()
        @JavascriptInterface fun complete(result: String) {
            if (delivered.compareAndSet(false, true)) callback(result)
        }
    }
}
