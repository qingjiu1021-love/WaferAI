package com.example.waferai.data.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DeepSeekClient(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(120,TimeUnit.SECONDS).callTimeout(150,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build(),
    private val endpoint: String = "https://api.deepseek.com/chat/completions"
) {
    suspend fun analyze(
        apiKey: String,
        model: String,
        evidence: JSONObject,
        requestEndpoint: String = endpoint,
        provider: AiProvider = AiProvider.DEEPSEEK,
        protocol: AiProtocol = AiProtocol.OPENAI,
        preset: AnalysisPreset = AnalysisPreset.ENGINEERING
    ): String = complete(
        apiKey, model, requestEndpoint, provider, protocol,
        JSONArray().put(chatMessage("system", AnalysisEvidence.systemFor(preset)))
            .put(chatMessage("user", evidence.toString()))
    )

    internal suspend fun chat(request: AiRequest, messages: JSONArray, thinking: ChatThinking = ChatThinking.DEFAULT): String = complete(
        request.key, request.profile.model, request.profile.endpoint, request.profile.provider,
        request.profile.protocol, messages, allowPartial = true, thinking = thinking
    )

    internal suspend fun test(request: AiRequest) {
        chat(request, JSONArray().put(chatMessage("user", "Reply with OK only.")))
    }

    internal suspend fun listModels(request: AiRequest): List<String> {
        val profile = request.profile
        check(profile.provider != AiProvider.BEDROCK || !modelCatalogUrl(profile).host.startsWith("bedrock-runtime.")) {
            "当前 Bedrock 地址不提供模型目录，请手动添加对应地区的模型 ID。"
        }
        val httpRequest = authenticated(request.key, profile.protocol).url(modelCatalogUrl(profile)).build()
        val json = fetchJson(httpRequest, profile.provider, 1024 * 1024)
        val rows = checkNotNull(json.optJSONArray("data")) { "服务未返回模型目录，请手动添加模型 ID。" }
        return (0 until rows.length()).asSequence().mapNotNull { index ->
            val id = rows.optJSONObject(index)?.opt("id") as? String ?: return@mapNotNull null
            runCatching { modelId(profile.provider, id) }.getOrNull()
        }.distinct().take(200).toList()
    }

    private suspend fun complete(
        apiKey: String, model: String, requestEndpoint: String, provider: AiProvider, protocol: AiProtocol,
        messages: JSONArray,
        allowPartial: Boolean = false, thinking: ChatThinking = ChatThinking.DEFAULT
    ): String {
        val thinkingApi = thinkingApi(provider, protocol, model)
        val effort = thinking.takeIf { it in thinkingApi.options() } ?: ChatThinking.DEFAULT
        val budget = when (effort) {
            ChatThinking.DEFAULT, ChatThinking.OFF -> 2048
            ChatThinking.MAX -> 16384
            else -> 8192
        }
        val body = JSONObject().put("model", model).put("stream", false)
        addThinking(body, provider, protocol, thinkingApi, effort)
        if (protocol == AiProtocol.RESPONSES) {
            body.put("store", false).put("max_output_tokens", budget)
            return responses(apiKey, requestEndpoint, provider, messages, body, allowPartial)
        }
        val tokenField = if (protocol == AiProtocol.OPENAI &&
            (provider == AiProvider.OPENAI || (provider == AiProvider.CUSTOM &&
                requestEndpoint.toHttpUrlOrNull()?.host == "api.openai.com"))) {
            "max_completion_tokens"
        } else "max_tokens"
        body.put(tokenField, budget)
        if (protocol == AiProtocol.ANTHROPIC) {
            addAnthropicMessages(body, messages)
        } else {
            body.put("messages", messages)
        }
        val request = authenticated(apiKey, protocol).url(requestEndpoint)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val json = fetchJson(request, provider, 256 * 1024)
        val (finish, value) = if (protocol == AiProtocol.ANTHROPIC) {
            val reason = when (json.optString("stop_reason")) {
                "end_turn", "stop_sequence" -> "stop"
                "max_tokens" -> "length"
                "refusal" -> "content_filter"
                else -> json.opt("stop_reason") as? String
            }
            reason to json.optJSONArray("content")
        } else {
            val choice = checkNotNull(json.optJSONArray("choices")?.optJSONObject(0)) {
                "服务未返回对话结果，请检查接口、协议和模型 ID。"
            }
            (choice.opt("finish_reason") as? String) to choice.optJSONObject("message")?.opt("content")
        }
        val truncated = finish == "length" && allowPartial
        check(finish != "content_filter") { "服务拦截了这次回复，请调整问题后重试。" }
        check(finish == "stop" || truncated || (finish.isNullOrBlank() && allowPartial)) {
            "AI 未返回完整回复，请缩短问题或切换模型后重试。"
        }
        val content = when (value) {
            is String -> value
            is JSONArray -> (0 until value.length()).mapNotNull { index ->
                val part = value.optJSONObject(index)
                if (part?.optString("type") == "text") part.opt("text") as? String else null
            }.joinToString("")
            else -> ""
        }.trim()
        check(content.isNotEmpty() && content.length <= 16000) { "AI 响应为空或过长，请重试。" }
        return if (truncated) "$content\n\n（已达到模型输出上限，可继续追问。）" else content
    }

    private suspend fun responses(key: String, endpoint: String, provider: AiProvider,
                                  messages: JSONArray, body: JSONObject, allowPartial: Boolean): String {
        val input = JSONArray()
        for (index in 0 until messages.length()) {
            val message = messages.getJSONObject(index)
            val content = message.get("content")
            val parts = if (content is JSONArray) JSONArray().apply {
                for (i in 0 until content.length()) {
                    val item = content.getJSONObject(i)
                    when (item.getString("type")) {
                        "text" -> put(JSONObject().put("type", "input_text").put("text", item.getString("text")))
                        "image_url" -> put(JSONObject().put("type", "input_image").put("detail", "auto")
                            .put("image_url", item.getJSONObject("image_url").getString("url")))
                    }
                }
            } else content
            input.put(JSONObject().put("role", message.getString("role")).put("content", parts))
        }
        body.put("input", input)
        val request = authenticated(key, AiProtocol.RESPONSES).url(endpoint)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val json = fetchJson(request, provider, 256 * 1024)
        val reason = json.optJSONObject("incomplete_details")?.optString("reason")
        val status = json.optString("status")
        val truncated = allowPartial && status == "incomplete" && reason == "max_output_tokens"
        check(status == "completed" || truncated) { "模型未完成 Responses 请求，请检查模型权限或缩短问题。" }
        val output = json.optJSONArray("output") ?: JSONArray()
        val content = buildString {
            for (index in 0 until output.length()) {
                val item = output.optJSONObject(index) ?: continue
                if (item.optString("type") != "message") continue
                val parts = item.optJSONArray("content") ?: continue
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.optString("type") == "output_text") append(part.optString("text"))
                }
            }
        }.trim()
        check(content.isNotEmpty() && content.length <= 16000) { "AI 响应为空或过长，请重试。" }
        return if (truncated) "$content\n\n（已达到模型输出上限，可继续追问。）" else content
    }

    private fun authenticated(key: String, protocol: AiProtocol) = Request.Builder()
        .header("Accept", "application/json").apply {
            if (protocol == AiProtocol.ANTHROPIC) {
                header("x-api-key", key)
                header("anthropic-version", "2023-06-01")
            } else {
                header("Authorization", "Bearer $key")
            }
        }

    private suspend fun fetchJson(request: Request, provider: AiProvider, maximumBytes: Int): JSONObject {
        val text = suspendCancellableCoroutine<String> { continuation ->
            val call=client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object: Callback {
                override fun onFailure(call: Call,error: IOException) {
                    if(continuation.isActive) continuation.resumeWithException(IOException("无法连接 ${provider.title}，请检查手机网络后重试。",error))
                }
                override fun onResponse(call: Call,response: Response) {
                    response.use {
                        try {
                            if (!response.isSuccessful) {
                                val error = apiError(response.peekBody(4096).string())
                                throw IllegalStateException(errorMessage(provider, response.code, error))
                            }
                            val stream=requireNotNull(response.body).byteStream()
                            val output=ByteArrayOutputStream(); val buffer=ByteArray(4096)
                            while(true) {
                                val count=stream.read(buffer); if(count<0) break
                                check(output.size() + count <= maximumBytes) { "AI 响应过长，请重试。" }
                                output.write(buffer,0,count)
                            }
                            val bytes=output.toByteArray()
                            if(continuation.isActive) continuation.resume(String(bytes,Charsets.UTF_8))
                        } catch(error: Exception) { if(continuation.isActive) continuation.resumeWithException(error) }
                    }
                }
            })
        }
        val json = try {
            JSONObject(text)
        } catch (_: JSONException) {
            error("服务未返回 JSON，请检查 API 地址和协议。")
        }
        if (!json.isNull("error")) {
            val error = json.optJSONObject("error")
            error(errorMessage(provider, error?.optInt("code", 0) ?: 0, error))
        }
        return json
    }

    private fun apiError(body: String): JSONObject? =
        runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()

    private fun errorMessage(provider: AiProvider, status: Int, error: JSONObject?): String {
        val code = error?.optString("code").orEmpty().lowercase()
        val type = error?.optString("type").orEmpty().lowercase()
        val reason = when {
            status == 401 || code in setOf("invalid_api_key", "api_key_invalid") || type == "authentication_error" ->
                "服务拒绝了 API 密钥，请使用当前平台的开发者密钥并在管理模型中重新填写。"
            status == 402 || code == "insufficient_quota" || code == "insufficient_balance" ->
                "余额或额度不足，请在服务平台查看账户用量。"
            status == 404 || code == "model_not_found" ->
                "找不到接口或模型，请核对 API 地址、模型 ID 和账户访问权限。"
            status == 403 -> "当前密钥或网络地区没有访问权限，请查看服务平台的模型权限。"
            status == 429 -> "请求过于频繁或达到额度限制，请稍后重试。"
            status in 300..399 -> "接口要求跳转，请填写最终的 HTTPS API 地址。"
            status == 400 || status == 422 -> "服务拒绝了请求，请检查模型 ID 及其支持的输入类型。"
            else -> "服务暂时无法提供回复，请稍后重试。"
        }
        val statusLabel = if (status >= 300) "（HTTP $status）" else ""
        return "${provider.title}$statusLabel $reason"
    }
}

internal fun chatMessage(role: String, content: String) = JSONObject().put("role", role).put("content", content)

private fun addThinking(body: JSONObject, provider: AiProvider, protocol: AiProtocol,
                        api: ThinkingApi?, effort: ChatThinking) {
    if (effort == ChatThinking.DEFAULT) {
        if (provider == AiProvider.DEEPSEEK && protocol == AiProtocol.OPENAI) {
            body.put("thinking", JSONObject().put("type", "disabled"))
        }
        return
    }
    val value = if (effort == ChatThinking.OFF) "none" else effort.name.lowercase()
    if (protocol == AiProtocol.RESPONSES || api == ThinkingApi.ROUTER) {
        body.put("reasoning", JSONObject().put("effort", value))
        return
    }
    if (api == ThinkingApi.DEEPSEEK) {
        body.put("thinking", JSONObject().put("type", if (effort == ChatThinking.OFF) "disabled" else "enabled"))
        if (effort == ChatThinking.OFF) return
    }
    if (protocol == AiProtocol.ANTHROPIC) {
        body.put("output_config", JSONObject().put("effort", value))
    } else {
        body.put("reasoning_effort", value)
    }
}

private fun addAnthropicMessages(body: JSONObject, messages: JSONArray) {
    val system = mutableListOf<String>()
    val conversation = JSONArray()
    for (index in 0 until messages.length()) {
        val message = messages.getJSONObject(index)
        val role = message.getString("role")
        val content = message.get("content")
        if (role == "system") {
            system += content as String
        } else {
            val blocks = if (content is JSONArray) JSONArray().apply {
                for (blockIndex in 0 until content.length()) {
                    val block = content.getJSONObject(blockIndex)
                    if (block.getString("type") == "image_url") {
                        val data = block.getJSONObject("image_url").getString("url")
                        put(JSONObject().put("type", "image").put("source", JSONObject()
                            .put("type", "base64").put("media_type", data.substringAfter("data:").substringBefore(';'))
                            .put("data", data.substringAfter(','))))
                    } else put(block)
                }
            } else content
            conversation.put(JSONObject().put("role", role).put("content", blocks))
        }
    }
    if (system.isNotEmpty()) body.put("system", system.joinToString("\n\n"))
    body.put("messages", conversation)
}
