package com.example.waferai.data.remote

import android.content.Context
import com.example.waferai.BuildConfig
import com.example.waferai.data.repository.verifyHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class ServerConnection(context: Context) {
    private val preferences = context.getSharedPreferences("server_settings", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()
    private val probeClient = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    private data class ActiveServer(val address: String, val api: WaferApi)
    private val initialAddress = runCatching {
        ServerAddress.normalize(preferences.getString("address", BuildConfig.API_BASE_URL).orEmpty())
    }.getOrDefault(BuildConfig.API_BASE_URL)
    @Volatile private var active = ActiveServer(initialAddress, createApi(initialAddress))

    val address: String get() = active.address
    val api: WaferApi get() = active.api

    private fun createApi(address: String, httpClient: OkHttpClient = client): WaferApi = Retrofit.Builder()
        .baseUrl(address).client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build().create(WaferApi::class.java)

    suspend fun checkAndSave(input: String): Boolean {
        val candidate = ServerAddress.normalize(input)
        verifyHealth(createApi(candidate, probeClient).health())
        return withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            val changed = active.address != candidate
            if (changed) {
                val replacement = ActiveServer(candidate, createApi(candidate))
                check(preferences.edit().putString("address", candidate).commit()) { "无法保存服务地址，请重试。" }
                active = replacement
            }
            changed
        }
    }
}
