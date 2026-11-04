package com.example.waferai.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerAddress {
    const val EMULATOR = "http://10.0.2.2:8000/"
    const val USB = "http://127.0.0.1:8000/"

    fun normalize(input: String): String {
        val value = input.trim()
        require(value.isNotEmpty()) { "请输入电脑上的服务地址。" }
        require(value.startsWith("http://", true) || value.startsWith("https://", true)) {
            "地址须以 http:// 或 https:// 开头，例如 http://192.168.1.10:8000。"
        }
        require(value.none { it.isWhitespace() || it == '\\' }) { "地址中不能包含空格或反斜杠。" }
        val url = requireNotNull(value.toHttpUrlOrNull()) { "服务地址格式不正确，请检查地址和端口。" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "服务地址不能包含用户名或密码。" }
        require(url.encodedPath == "/" && url.query == null && url.fragment == null) {
            "只需填写服务根地址，请去掉 /docs、/api 等路径及查询参数。"
        }
        require(url.host != "0.0.0.0" && url.host != "::") { "请填写电脑的实际地址，不能使用 0.0.0.0。" }
        return url.toString()
    }
}
