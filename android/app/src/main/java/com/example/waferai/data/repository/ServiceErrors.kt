package com.example.waferai.data.repository

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import retrofit2.HttpException

fun serviceErrorMessage(error: Exception): String = when (error) {
    is SocketTimeoutException -> "连接超时。请确认电脑服务正在运行，并检查服务设置中的地址。"
    is UnknownHostException -> "无法找到该地址，请检查服务设置中的电脑地址。"
    is SSLException -> "无法验证此 HTTPS 服务的证书，请检查服务地址和证书。"
    is HttpException -> "该地址未提供可用的 WaferAI 接口，请检查地址和服务状态。"
    is IllegalArgumentException, is IllegalStateException -> error.message ?: "服务响应异常，请检查地址。"
    is IOException -> "无法连接分析服务。请确认电脑服务已启动，并检查服务设置或 USB 连接。"
    else -> "服务响应异常，请确认该地址运行的是 WaferAI 服务。"
}
