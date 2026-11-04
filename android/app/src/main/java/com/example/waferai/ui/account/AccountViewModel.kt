package com.example.waferai.ui.account

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.waferai.data.account.*
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class AccountState(
    val endpoint: String = "", val user: AccountUser? = null, val busy: Boolean = false,
    val providers: List<LoginProvider> = emptyList(), val development: Boolean = false,
    val email: Boolean = false, val phone: Boolean = false, val connected: Boolean = false,
    val message: String? = null, val error: Boolean = false, val browser: String? = null,
    val codeUntil: Long = 0, val polling: Boolean = false,
    val saved: List<SavedAccount> = emptyList(), val bindingComplete: Int = 0
)

internal class AccountViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AccountStore(application)
    private val client = AccountClient()
    private val mutable = MutableStateFlow(AccountState(endpoint = store.endpoint(), user = store.token()?.let { store.user() }, saved = store.savedAccounts()))
    val state = mutable.asStateFlow()
    private var pollJob: Job? = null
    init { store.pending()?.let { startPolling(it) } }

    fun checkEndpoint(value: String) = action {
        check(mutable.value.user == null || value.trimEnd('/') == mutable.value.endpoint) { "请先退出当前账号，再更换服务地址。" }
        val result = client.request(value, "/api/v1/auth/capabilities")
        store.endpoint(value)
        val providers = Gson().fromJson(result.getJSONArray("providers").toString(), Array<LoginProvider>::class.java).toList()
        mutable.value = mutable.value.copy(endpoint = store.endpoint(), connected = true, providers = providers,
            development = result.optBoolean("development"), email = result.optBoolean("email"), phone = result.optBoolean("phone"),
            message = "账号服务已连接。", error = false)
        if (mutable.value.user != null) {
            val profile = client.request(store.endpoint(), "/api/v1/auth/me", token = store.token())
            store.profile(Gson().fromJson(profile.toString(), AccountUser::class.java))
            refreshAccounts()
        }
    }

    fun hint(text: String) { mutable.value = mutable.value.copy(message = text, error = false) }
    fun browserOpened() { mutable.value = mutable.value.copy(browser = null) }
    fun browserFailed() { cancelSocial(); mutable.value = mutable.value.copy(browser = null, message = "无法打开浏览器，请检查系统浏览器。", error = true) }

    fun sendCode(target: String, reset: Boolean) = action {
        val result = client.request(mutable.value.endpoint, "/api/v1/auth/codes", JSONObject().put("target", target).put("purpose", if (reset) "reset" else "register"))
        mutable.value = mutable.value.copy(codeUntil = System.currentTimeMillis() + result.optInt("retry_after", 60) * 1000L, message = result.getString("message"))
    }

    fun submit(mode: String, target: String, password: String, code: String, name: String, agreed: Boolean) = action {
        require(agreed) { "请先阅读并同意使用条款和隐私政策。" }
        val body = JSONObject().put("target", target).put("password", password).put("code", code).put("name", name).put("agreed", agreed)
        val result = client.request(mutable.value.endpoint, "/api/v1/auth/" + when (mode) { "register" -> "register"; "reset" -> "reset-password"; else -> "login" }, body)
        if (mode == "reset") mutable.value = mutable.value.copy(message = result.getString("message"))
        else { store.save(result); refreshAccounts(); mutable.value = mutable.value.copy(message = "登录成功。") }
    }

    fun logout() = action {
        client.request(mutable.value.endpoint, "/api/v1/auth/logout", JSONObject(), store.token())
        store.clear()
        refreshAccounts()
        mutable.value = mutable.value.copy(message = "已退出当前账号。")
    }

    fun forgetLocalSession() {
        cancelSocial()
        store.clear()
        refreshAccounts()
        mutable.value = mutable.value.copy(polling = false, message = "已移除此设备的当前登录状态。联网后仍可重新登录。")
    }

    fun deleteAccount() = action {
        val result = client.request(mutable.value.endpoint, "/api/v1/auth/account", token = store.token(), method = "DELETE")
        store.clear()
        refreshAccounts()
        mutable.value = mutable.value.copy(message = result.getString("message"))
    }

    private fun refreshAccounts() { mutable.value = mutable.value.copy(endpoint = store.endpoint(), user = store.token()?.let { store.user() }, saved = store.savedAccounts()) }

    fun addAccount() {
        if (mutable.value.busy || mutable.value.polling) return
        store.pause(); refreshAccounts()
        mutable.value = mutable.value.copy(message = "登录另一个账号，已有账号仍保留在此设备。", codeUntil = 0)
    }

    fun switchAccount(key: String) = action {
        val (account, token) = store.credential(key)
        val sameService = account.origin == mutable.value.endpoint
        val profile = try { client.request(account.origin, "/api/v1/auth/me", token = token) }
        catch (error: AccountFailure) {
            if (error.status == 401) { store.removeSaved(key); refreshAccounts(); error("该账号登录已过期，请重新登录。") }
            throw error
        }
        store.activate(key, Gson().fromJson(profile.toString(), AccountUser::class.java))
        refreshAccounts()
        mutable.value = mutable.value.copy(connected = sameService && mutable.value.connected,
            providers = if (sameService) mutable.value.providers else emptyList(),
            email = sameService && mutable.value.email, phone = sameService && mutable.value.phone,
            development = sameService && mutable.value.development, message = "账号已切换。本机工作空间保持不变。", codeUntil = 0)
    }

    fun removeSavedAccount(key: String) {
        if (mutable.value.busy || mutable.value.polling || key == "${store.endpoint()}|${store.user()?.id}") return
        store.removeSaved(key); refreshAccounts()
        hint("已从此设备移除该账号，下次使用需重新登录。")
    }

    fun sendBindingCode(target: String) = action {
        val result = client.request(store.endpoint(), "/api/v1/auth/bind/codes", JSONObject().put("target", target), store.token())
        mutable.value = mutable.value.copy(codeUntil = System.currentTimeMillis() + result.optInt("retry_after", 60) * 1000L, message = result.getString("message"))
    }

    fun bind(target: String, password: String, code: String) = action {
        val result = client.request(store.endpoint(), "/api/v1/auth/bind", JSONObject().put("target", target).put("password", password).put("code", code), store.token())
        store.profile(Gson().fromJson(result.getJSONObject("user").toString(), AccountUser::class.java)); refreshAccounts()
        mutable.value = mutable.value.copy(message = result.getString("message"), bindingComplete = mutable.value.bindingComplete + 1)
    }

    fun help() = action {
        check(mutable.value.user != null) { "请先登录，再打开帮助中心。" }
        val result = client.request(mutable.value.endpoint, "/api/v1/auth/web-session", JSONObject(), store.token())
        val path = result.getString("path")
        require(path.startsWith("/auth/handoff?ticket=") && !path.contains('#') && !path.contains(' '))
        mutable.value = mutable.value.copy(browser = mutable.value.endpoint + path)
    }

    fun social(provider: String, agreed: Boolean) = action {
        require(agreed) { "请先阅读并同意使用条款和隐私政策。" }
        val origin = mutable.value.endpoint
        val result = client.request(origin, "/api/v1/auth/social/start", JSONObject().put("provider", provider).put("agreed", true))
        val url = result.getString("authorization_url")
        // Authorization always starts at the configured account service, then redirects in the browser.
        require(url.startsWith("$origin/auth/social/launch/")) { "授权地址与当前账号服务不一致，请检查服务器公开地址。" }
        val pending = PendingAccountLogin(origin, result.getString("poll_token"), System.currentTimeMillis() + result.optInt("expires_in", 600).coerceIn(1, 600) * 1000L)
        store.pending(pending)
        mutable.value = mutable.value.copy(browser = url, message = "请在浏览器完成授权，然后返回 App。")
        startPolling(pending)
    }

    private fun startPolling(pending: PendingAccountLogin) {
        pollJob?.cancel()
        mutable.value = mutable.value.copy(polling = true)
        pollJob = viewModelScope.launch {
            try {
                var networkFailures = 0
                while (System.currentTimeMillis() < pending.expiresAt) {
                    delay(2000)
                    val data = try { client.request(pending.origin, "/api/v1/auth/social/poll", JSONObject().put("poll_token", pending.pollToken)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (error is AccountFailure && error.status in 400..499) throw error
                        networkFailures++
                        mutable.value = mutable.value.copy(message = "网络暂时中断，正在恢复授权连接…")
                        delay((networkFailures.coerceAtMost(5) * 2000).toLong())
                        continue
                    }
                    networkFailures = 0
                    if (!data.optBoolean("pending")) {
                        store.save(data)
                        refreshAccounts()
                        mutable.value = mutable.value.copy(polling = false, message = "授权登录成功。")
                        return@launch
                    }
                }
                store.clearPending()
                mutable.value = mutable.value.copy(polling = false, message = "授权已超时，请重新开始。", error = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { store.clearPending(); mutable.value = mutable.value.copy(polling = false, message = message(error), error = true) }
        }
    }

    fun cancelSocial() {
        val pending = store.pending()
        pollJob?.cancel()
        store.clearPending()
        mutable.value = mutable.value.copy(polling = false, message = "已停止等待授权。")
        pending?.let { viewModelScope.launch { runCatching { client.request(it.origin, "/api/v1/auth/social/cancel", JSONObject().put("poll_token", it.pollToken)) } } }
    }

    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy || mutable.value.polling) return
        mutable.value = mutable.value.copy(busy = true, message = null, error = false)
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (error is AccountFailure && error.status == 401 && mutable.value.user != null) { store.clear(); refreshAccounts() }
                mutable.value = mutable.value.copy(message = message(error), error = true)
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    private fun message(error: Exception) = when (error) {
        is AccountFailure, is IllegalArgumentException, is IllegalStateException -> error.message ?: "账号操作失败，请重试。"
        else -> "无法连接账号服务，请检查地址、网络和服务是否启动。"
    }
}
