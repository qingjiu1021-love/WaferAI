package com.example.waferai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waferai.data.ai.AiProfile
import com.example.waferai.data.ai.AiProfileStore
import com.example.waferai.data.ai.AiProvider
import com.example.waferai.data.ai.AiProtocol
import com.example.waferai.data.ai.AiConnection
import com.example.waferai.data.ai.AiModelEntry
import com.example.waferai.data.ai.AiRequest
import com.example.waferai.data.ai.DeepSeekClient
import com.example.waferai.data.ai.ChatThinking
import com.example.waferai.data.ai.thinkingOptions
import com.example.waferai.data.ai.SearchPlugin
import com.example.waferai.data.ai.SearchPluginApi
import com.example.waferai.data.ai.SearchPluginException
import com.example.waferai.data.ai.WaferKnowledge
import com.example.waferai.data.ai.KnowledgeTaskStore
import com.example.waferai.data.ai.chatMessage
import com.example.waferai.data.ai.parseAiKey
import com.example.waferai.data.ai.completionEndpoint
import com.example.waferai.data.local.OperationLog
import com.example.waferai.ui.settings.parseConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OnlineConnectionTest {
    @Test fun reasoningIsSentInTheSelectedProtocolAndIgnoredForUnsupportedModels() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = DeepSeekClient()
            val messages = JSONArray().put(chatMessage("user", "Explain wafer yield"))
            val deepseek = AiProfile("qa", "QA", AiProvider.DEEPSEEK, server.url("/chat/completions").toString(),
                "deepseek-v4-pro")
            val openai = AiProfile("qa", "QA", AiProvider.OPENAI, server.url("/v1/responses").toString(),
                "gpt-5.5", null, AiProtocol.RESPONSES)
            val claude = AiProfile("qa", "QA", AiProvider.ANTHROPIC, server.url("/v1/messages").toString(),
                "claude-sonnet-4-6")
            val unsupported = deepseek.copy(provider = AiProvider.CUSTOM, model = "ordinary-text-model")
            val choices = """{"choices":[{"finish_reason":"stop","message":{"content":"OK"}}]}"""
            suspend fun send(profile: AiProfile, level: ChatThinking, reply: String): JSONObject {
                server.enqueue(MockResponse().setBody(reply))
                assertEquals("OK", client.chat(AiRequest(profile, "test-reasoning-key"), messages, level))
                return JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            }
            assertTrue(ChatThinking.MAX in deepseek.thinkingOptions())
            val deepBody = send(deepseek, ChatThinking.MAX, choices)
            assertEquals("enabled", deepBody.getJSONObject("thinking").getString("type"))
            assertEquals("max", deepBody.getString("reasoning_effort"))
            assertTrue(deepBody.getInt("max_tokens") > 2048)
            val off = send(deepseek, ChatThinking.OFF, choices)
            assertEquals("disabled", off.getJSONObject("thinking").getString("type"))
            assertFalse(off.has("reasoning_effort"))
            val response = send(openai, ChatThinking.MEDIUM,
                """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"OK"}]}]}""")
            assertEquals("medium", response.getJSONObject("reasoning").getString("effort"))
            val claudeBody = send(claude, ChatThinking.LOW,
                """{"stop_reason":"end_turn","content":[{"type":"text","text":"OK"}]}""")
            assertEquals("low", claudeBody.getJSONObject("output_config").getString("effort"))
            assertTrue(unsupported.thinkingOptions().isEmpty())
            assertFalse(send(unsupported, ChatThinking.HIGH, choices).has("reasoning_effort"))
        }
    }

    @Test fun responsesTaskSendsThePromptToTheBoundModelAndStoresItsReply() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID()
        val prefs = "qa-task-profile-$suffix"
        val legacy = "qa-task-legacy-$suffix"
        val tasks = "qa-tasks-$suffix"
        try {
            MockWebServer().use { server ->
                server.start()
                val transport = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
                }.build()
                val profiles = AiProfileStore(context, prefs, legacy)
                val connection = AiConnection(null, AiProvider.CUSTOM, "QA", "https://example.org/v1",
                    "test-task-api-key", "test-task", AiProtocol.RESPONSES)
                profiles.save(connection, listOf(AiModelEntry("test-responses")))
                val profile = profiles.read().active!!
                val store = KnowledgeTaskStore(context, tasks)
                val task = store.add("QA task", "Explain wafer yield", 24, profile)
                server.enqueue(MockResponse().setBody("""{"error":null,"status":"completed","output":[
                    {"type":"reasoning"},{"type":"message","content":[{"type":"output_text","text":"Saved AI result"}]}]}"""))
                store.execute(task.id, profiles, DeepSeekClient(transport), scheduled = true)
                val sent = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/v1/responses", sent.path)
                assertEquals("Bearer test-task-api-key", sent.getHeader("Authorization"))
                val body = JSONObject(sent.body.readUtf8())
                assertEquals("test-responses", body.getString("model"))
                assertFalse(body.getBoolean("store"))
                assertFalse(body.has("messages"))
                assertEquals("Explain wafer yield", body.getJSONArray("input").getJSONObject(1).getString("content"))
                val result = KnowledgeTaskStore(context, tasks).tasks.value.single()
                assertEquals("Saved AI result", result.reply)
                assertFalse(result.lastFailed)
                profiles.save(AiConnection(profile.id, AiProvider.CUSTOM, "QA", "https://example.org/changed/v1",
                    "test-new-key", "test-task", AiProtocol.RESPONSES), listOf(AiModelEntry("test-responses")))
                store.execute(task.id, profiles, DeepSeekClient(transport), scheduled = true)
                assertEquals(1, server.requestCount)
                assertTrue(store.tasks.value.single().lastFailed)
                assertEquals("Saved AI result", store.tasks.value.single().reply)
            }
        } finally {
            listOf(prefs, legacy, tasks).forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("$legacy.aes") }
        }
    }

    @Test fun importedProviderSavesAndEditsMultipleModelsTogether() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID()
        val preferences = "qa-provider-$suffix"
        val legacy = "qa-legacy-$suffix"
        val json = """{"providerId":"test-gateway","displayName":"QA","baseUrl":"https://example.org/anthropic",
            "protocol":"anthropic","models":["first",{"id":"second","displayName":"Second model"}],"apiKey":"ignored-file-key"}"""
        val draft = parseConfig(json)
        assertEquals("", draft.key)
        assertEquals(listOf(AiModelEntry("first"), AiModelEntry("second", "Second model")), draft.models)
        assertEquals("https://example.org/anthropic/v1/messages", completionEndpoint(draft.address, draft.protocol))
        try {
            val store = AiProfileStore(context, preferences, legacy)
            store.save(draft.copy(key = "test-gateway-key").connection(), draft.models)
            val reopened = AiProfileStore(context, preferences, legacy)
            val first = reopened.read().profiles.first()
            val second = reopened.read().profiles.last()
            assertEquals("test-gateway", first.providerId)
            assertEquals(AiProtocol.ANTHROPIC, second.protocol)
            assertEquals("first", first.selectionName)
            assertEquals("Second model", second.selectionName)
            assertEquals("test-gateway-key", reopened.request(second.id).key)
            reopened.select(second.id)
            reopened.save(draft.copy(id = first.id, name = "Updated").connection(),
                listOf(draft.models.last(), AiModelEntry("third", "Third model")))
            val updated = reopened.read()
            assertEquals(listOf("second", "third"), updated.profiles.map(AiProfile::model))
            assertEquals(second.id, updated.activeId)
            assertEquals("Updated", updated.active?.name)
            assertEquals("Second model", updated.active?.selectionName)
            assertEquals("Third model", updated.profiles.last().selectionName)
            assertEquals("test-gateway-key", reopened.request(updated.profiles.last().id).key)
            reopened.renameModel(second.id, " Renamed ")
            val renamed = AiProfileStore(context, preferences, legacy).read()
            assertEquals("Renamed", renamed.active?.selectionName)
            assertEquals("Third model", renamed.profiles.last().selectionName)
            reopened.renameModel(second.id, "")
            assertEquals("second", reopened.read().active?.selectionName)
            try {
                reopened.save(draft.copy(key = "test-other-key").connection(), listOf(AiModelEntry("other")))
                fail("Duplicate provider ID should be rejected")
            } catch (_: IllegalArgumentException) { }
            assertEquals(2, reopened.read().profiles.size)
        } finally {
            context.getSharedPreferences(preferences, 0).edit().clear().commit()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("$legacy.aes") }
        }
    }

    @Test fun anthropicCatalogAndChatUseTheirOwnEndpointAndPayload() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                val original = chain.request()
                val local = server.url(original.url.encodedPath).newBuilder().encodedQuery(original.url.encodedQuery).build()
                chain.proceed(original.newBuilder().url(local).build())
            }.build()
            val profile = AiProfile("qa", "QA", AiProvider.CUSTOM, "https://example.org/gateway/v1/messages",
                "test-model", "test-gateway", AiProtocol.ANTHROPIC)
            val request = AiRequest(profile, "test-anthropic-key")
            val client = DeepSeekClient(transport)
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"test-model"},{"id":"second"},{"id":"second"}]}"""))
            assertEquals(listOf("test-model", "second"), client.listModels(request))
            val catalog = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", catalog.method)
            assertEquals("/gateway/v1/models", catalog.requestUrl?.encodedPath)
            assertEquals("test-anthropic-key", catalog.getHeader("x-api-key"))
            assertNull(catalog.getHeader("Authorization"))
            server.enqueue(MockResponse().setBody("""{"stop_reason":"end_turn","content":[
                {"type":"thinking","thinking":"private"},{"type":"text","text":"reply"}]}"""))
            val photo = JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64,AAAA"))
            val messages = JSONArray().put(chatMessage("system", "Instructions"))
                .put(JSONObject().put("role", "user").put("content", JSONArray().put(photo)))
            assertEquals("reply", client.chat(request, messages))
            val chat = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/gateway/v1/messages", chat.path)
            assertEquals("2023-06-01", chat.getHeader("anthropic-version"))
            val body = JSONObject(chat.body.readUtf8())
            assertEquals("Instructions", body.getString("system"))
            val image = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
            assertEquals("image", image.getString("type"))
            assertEquals("AAAA", image.getJSONObject("source").getString("data"))
        }
    }

    @Test fun openAiCatalogUsesSameGatewayPrefixAndBearerAuthentication() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"my-chat-model"}]}"""))
            val profile = AiProfile("qa", "QA", AiProvider.CUSTOM,
                server.url("/gateway/v1/chat/completions").toString(), "")
            assertEquals(listOf("my-chat-model"), DeepSeekClient().listModels(AiRequest(profile, "test-openai-key")))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/gateway/v1/models", request.path)
            assertEquals("Bearer test-openai-key", request.getHeader("Authorization"))
        }
    }

    @Test fun giteeUsesCurrentPublicEndpointAndParsesOnlyRepositoryLinks() {
        val request = SearchPluginApi.request(SearchPlugin.GITEE, "wafer map", null)
        assertEquals("/api/v5/search/repos", request.url.encodedPath)
        assertEquals("6", request.url.queryParameter("size"))
        assertEquals("wafer map", request.url.queryParameter("q"))
        val response = """{"total_count":2,"data":[
            {"title":"demo/wafer","url":"https://gitee.com/demo/wafer","description":"Wafer map"},
            {"title":"unrelated","url":"https://other.example/wafer"}
        ]}"""
        val result = SearchPluginApi.parse(SearchPlugin.GITEE, request, response).single()
        assertEquals("https://gitee.com/demo/wafer", result.source)
        assertEquals("demo/wafer", result.title)
    }

    @Test fun wikipediaFallsBackToSameLanguageMobileEndpoint() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("""{"pages":[{"id":12,"title":"Wafer","excerpt":"<span>Wafer</span> test"}]}"""))
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val local = server.url(request.url.encodedPath).newBuilder()
                    .addQueryParameter("host", request.url.host).build()
                chain.proceed(request.newBuilder().url(local).build())
            }.build()
            val knowledge = WaferKnowledge(context, OperationLog(context), transport)
            assertEquals(1, knowledge.test(SearchPlugin.WIKIPEDIA))
            val first = server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!
            val second = server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!
            assertEquals("/w/rest.php/v1/search/page", first.encodedPath)
            assertEquals("en.wikipedia.org", first.queryParameter("host"))
            assertEquals("en.m.wikipedia.org", second.queryParameter("host"))
        }
    }

    @Test fun googleUsesSerperCredentialsAndReportsRejectedKeyWithoutEchoingIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MockWebServer().use { server ->
            server.start()
            val key = "test-serper-secret"
            server.enqueue(MockResponse().setBody("""{"organic":[{"link":"https://example.org","title":"Wafer maps"}]}"""))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Unauthorized. $key"}"""))
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                assertEquals("google.serper.dev", chain.request().url.host)
                chain.proceed(chain.request().newBuilder().url(server.url("/search")).build())
            }.build()
            val knowledge = WaferKnowledge(context, OperationLog(context), transport)
            assertEquals(1, knowledge.test(SearchPlugin.GOOGLE, "X-API-KEY: $key"))
            val sent = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(key, sent.getHeader("X-API-KEY"))
            assertEquals("wafer map", JSONObject(sent.body.readUtf8()).getString("q"))
            try {
                knowledge.test(SearchPlugin.GOOGLE, key)
                fail("Expected authentication error")
            } catch (error: SearchPluginException) {
                assertEquals(403, error.status)
                assertTrue(error.message.orEmpty().contains("Serper API Key"))
                assertFalse(error.message.orEmpty().contains(key))
            }
        }
    }

    @Test fun modelTestAndChatShareRequestHandlingWithClearAuthenticationErrors() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val key = parseAiKey(" Bearer \"test-model-key\" ")
            val profile = AiProfile("qa", "QA", AiProvider.DEEPSEEK,
                server.url("/chat/completions").toString(), "deepseek-flash")
            val model = AiRequest(profile, key)
            val client = DeepSeekClient()
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
            client.test(model)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer test-model-key", request.getHeader("Authorization"))
            assertEquals("deepseek-flash", JSONObject(request.body.readUtf8()).getString("model"))
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"length","message":{"content":"Partial reply"}}]}"""))
            val messages = JSONArray().put(chatMessage("user", "Hello"))
            val reply = client.chat(model, messages)
            assertTrue(reply.startsWith("Partial reply"))
            assertTrue(reply.contains("输出上限"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid key: $key"}}"""))
            try {
                client.chat(model, messages)
                fail("Expected authentication error")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("HTTP 401"))
                assertFalse(error.message.orEmpty().contains(key))
            }
        }
    }
}
