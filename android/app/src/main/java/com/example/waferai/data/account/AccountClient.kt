package com.example.waferai.data.account

import android.content.Context
import android.os.Build
import com.example.waferai.BuildConfig
import com.example.waferai.data.ai.AiKeyVault
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class AccountIdentity(val kind: String = "", val label: String = "")
internal data class AccountUser(val id: String = "", val name: String = "", val identities: List<AccountIdentity> = emptyList())
internal data class LoginProvider(val id: String, val name: String, val available: Boolean)
internal data class PendingAccountLogin(val origin: String, val pollToken: String, val expiresAt: Long)
internal data class SavedAccount(val key: String, val origin: String, val user: AccountUser)
private data class SavedSession(val origin: String, val user: AccountUser, val token: String) {
    val key get() = "$origin|${user.id}"
}
internal class AccountFailure(val status: Int, message: String) : Exception(message)

internal fun accountOrigin(input: String): String {
    val url = input.trim().trimEnd('/').toHttpUrlOrNull() ?: throw IllegalArgumentException("请输入有效的账号服务地址。")
    require(url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null) {
        "请只填写服务域名和端口，不要包含路径或密钥。"
    }
    require(url.isHttps || (url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost", "10.0.2.2"))) {
        "账号服务必须使用 HTTPS。本机测试可使用 localhost、127.0.0.1 或 10.0.2.2。"
    }
    return url.toString().trimEnd('/')
}

internal class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("account_session", Context.MODE_PRIVATE)
    private val vault = AiKeyVault("waferai-account-session-v1")
    private val gson = Gson()
    init {
        if (!prefs.getBoolean("cloud_default_420", false)) {
            val previous = prefs.getString("endpoint", null)
            val edit = prefs.edit().putBoolean("cloud_default_420", true)
            if (!prefs.contains("token") && previous in setOf(null, "", "http://10.0.2.2:8010", "http://127.0.0.1:8010"))
                edit.putString("endpoint", BuildConfig.ACCOUNT_BASE_URL)
            check(edit.commit()) { "无法更新账号服务地址。" }
        }
    }
    fun endpoint(): String = prefs.getString("endpoint", null) ?: BuildConfig.ACCOUNT_BASE_URL
    fun endpoint(value: String) { check(prefs.edit().putString("endpoint", accountOrigin(value)).commit()) { "无法保存账号服务地址。" } }
    fun user(): AccountUser? = runCatching { prefs.getString("user", null)?.let { gson.fromJson(it, AccountUser::class.java) } }.getOrNull()
    fun token(): String? = runCatching {
        prefs.getString("token", null)?.let { vault.decrypt(it, requireNotNull(prefs.getString("iv", null))) }
    }.getOrNull()
    fun save(result: JSONObject) {
        val user = gson.fromJson(result.getJSONObject("user").toString(), AccountUser::class.java)
        require(user.id.isNotBlank())
        val (encrypted, iv) = vault.encrypt(result.getString("token"))
        check(prefs.edit().putString("token", encrypted).putString("iv", iv).putString("user", gson.toJson(user)).commit()) { "无法保存登录状态。" }
        rememberCurrent()
        clearPending()
    }
    private fun sessions(): List<SavedSession> {
        val encrypted = prefs.getString("accounts", null) ?: return emptyList()
        return gson.fromJson(vault.decrypt(encrypted, requireNotNull(prefs.getString("accounts_iv", null))), Array<SavedSession>::class.java).toList()
    }
    private fun sessions(values: List<SavedSession>) {
        val (encrypted, iv) = vault.encrypt(gson.toJson(values))
        check(prefs.edit().putString("accounts", encrypted).putString("accounts_iv", iv).commit()) { "无法保存账号列表。" }
    }
    fun rememberCurrent() {
        val token = token() ?: return
        val user = user() ?: return
        val current = SavedSession(endpoint(), user, token)
        sessions(sessions().filterNot { it.key == current.key } + current)
    }
    fun savedAccounts(): List<SavedAccount> {
        rememberCurrent()
        return sessions().map { SavedAccount(it.key, it.origin, it.user) }
    }
    fun credential(key: String): Pair<SavedAccount, String> {
        val value = sessions().firstOrNull { it.key == key } ?: error("此账号已移除，请重新登录。")
        return SavedAccount(value.key, value.origin, value.user) to value.token
    }
    fun removeSaved(key: String) { sessions(sessions().filterNot { it.key == key }) }
    fun activate(key: String, verified: AccountUser) {
        val (account, token) = credential(key)
        require(account.user.id == verified.id) { "服务返回了不匹配的身份，请重新登录。" }
        endpoint(account.origin)
        save(JSONObject().put("token", token).put("user", JSONObject(gson.toJson(verified))))
    }
    fun profile(user: AccountUser) {
        require(user.id == this.user()?.id)
        check(prefs.edit().putString("user", gson.toJson(user)).commit())
        rememberCurrent()
    }
    fun pause() { rememberCurrent(); clearActive() }
    fun pending(): PendingAccountLogin? = runCatching {
        prefs.getString("pending", null)?.let { encrypted ->
            val json = vault.decrypt(encrypted, requireNotNull(prefs.getString("pending_iv", null)))
            gson.fromJson(json, PendingAccountLogin::class.java).takeIf { it.expiresAt > System.currentTimeMillis() && it.pollToken.length in 20..100 && accountOrigin(it.origin) == endpoint() }
        }
    }.getOrNull()
    fun pending(value: PendingAccountLogin) {
        val (encrypted, iv) = vault.encrypt(gson.toJson(value))
        check(prefs.edit().putString("pending", encrypted).putString("pending_iv", iv).commit()) { "无法保存授权进度。" }
    }
    fun clearPending() { check(prefs.edit().remove("pending").remove("pending_iv").commit()) { "无法清除授权进度。" } }
    private fun clearActive() { check(prefs.edit().remove("token").remove("iv").remove("user").remove("pending").remove("pending_iv").commit()) { "无法清除登录状态。" } }
    fun clear() {
        user()?.let { removeSaved("${endpoint()}|${it.id}") }
        clearActive()
    }
}

internal class AccountClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {
    suspend fun request(origin: String, path: String, body: JSONObject? = null, token: String? = null, method: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val base = accountOrigin(origin)
        require(path.startsWith("/api/v1/auth/"))
        val builder = Request.Builder().url(base + path).header("Accept", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        when (method ?: if (body == null) "GET" else "POST") {
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
            "DELETE" -> builder.delete()
        }
        client.newCall(builder.build()).execute().use { response ->
            val content = response.body?.string().orEmpty()
            require(content.length <= 128_000) { "账号服务返回内容异常。" }
            val result = runCatching { JSONObject(content) }.getOrElse { throw AccountFailure(response.code, "账号服务返回格式异常，请检查地址。") }
            if (!response.isSuccessful) throw AccountFailure(response.code, result.optString("message").ifBlank { "请求失败，请检查填写内容后重试。" })
            result
        }
    }
}
