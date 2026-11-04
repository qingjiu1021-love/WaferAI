package com.example.waferai.data.repository

import com.example.waferai.data.remote.WaferApi
import com.example.waferai.data.remote.HealthResponse

class HealthRepository(private val api: () -> WaferApi) {
    suspend fun checkConnection() {
        verifyHealth(api().health())
    }
}

fun verifyHealth(response: HealthResponse) {
    check(response.status == "ok" && response.service == "WaferAI" && response.apiVersion == "v1") {
        "该地址不是兼容的 WaferAI 服务。"
    }
}
