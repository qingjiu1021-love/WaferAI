package com.example.waferai

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.waferai.data.local.*
import com.example.waferai.data.ai.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import com.example.waferai.ui.chat.ChatViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.example.waferai.data.report.ReportSnapshot
import com.example.waferai.data.report.WaferPdfReport

@RunWith(AndroidJUnit4::class)
class StandaloneTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun json(lot: String="QA",wafer: String="W1",map: String="[[0,1,2],[1,1,1]]") = "{\"lot_code\":\"$lot\",\"wafer_code\":\"$wafer\",\"map\":$map}".toByteArray()

    @Test fun nativeReportPaginatesLongAnalysis() {
        val wafer=WaferJson.parse(json(),"qa-only.json").detail.copy(id=1)
        val prediction=MobilePredictor(context).predict(wafer).copy(id=1)
        val content=(1..80).joinToString("\n") { "第${it}段：这只是分页测试文字，不是真实 DeepSeek 解析。请核对晶圆统计，结合电测与工艺记录复核。" }+"\n分页测试结束标记。"
        val analysis=AiAnalysis(1,1,1,"test-only",content,"2026-11-03T00:00:00Z")
        val bytes=WaferPdfReport(ReportSnapshot(wafer,prediction,analysis=analysis)).generate()
        assertTrue(bytes.size>1000)
        context.openFileOutput("qa-long-analysis.pdf",0).use { it.write(bytes) }
    }

    @Test fun mobileModelMatchesFrozenTorchFixtures() {
        val fixtures=JsonParser.parseString(instrumentation.context.assets.open("mobile-parity.json").bufferedReader().use { it.readText() }).asJsonArray
        val predictor=MobilePredictor(context)
        fixtures.forEach { fixture ->
            val data=fixture.asJsonObject
            val map=data.getAsJsonArray("map").map { row -> row.asJsonArray.map { it.asInt } }
            assertEquals(data["prepared_sha256"].asString,WaferJson.sha256(MobilePreprocessing.bins(map)))
            val expected=data.getAsJsonArray("scores").map { it.asDouble }; val actual=predictor.scores(map)
            actual.indices.forEach { assertEquals(expected[it],actual[it],0.0001) }
            assertEquals(expected.indices.maxBy { expected[it] },actual.indices.maxBy { actual[it] })
        }
    }

    @Test fun localStoragePersistsAndAggregatesWithoutServer() {
        val name="qa-${UUID.randomUUID()}.db"
        var store=LocalWaferStore(context,name)
        try {
            val first=store.importJson(json(map="[[1]]"),"first.json")
            assertFalse(first.alreadyImported)
            assertTrue(store.importJson(json(map="[[1]]"),"repeat.json").alreadyImported)
            try { store.importJson(json(map="[[2]]"),"conflict.json"); fail("Expected conflict") } catch(_: IllegalArgumentException) { }
            val second=store.importJson(json(wafer="W2",map="[[2,2,2],[2,2,2],[2,2,2]]"),"second.json")
            val lot=store.listLots(20,0).single()
            assertEquals(2,lot.waferCount); assertEquals(1L,lot.goodCount); assertEquals(9L,lot.badCount); assertEquals(0.1,lot.yieldRate!!,0.0)
            val overview = store.overview()
            assertEquals(0.1, overview.yieldRate!!, 0.0)
            assertEquals(listOf(1, 0, 1), overview.yieldBands)
            assertEquals(second.wafer.id, store.listWafers(20, 0, lot.id, "SECOND").single().id)
            assertTrue(store.listWafers(20, 0, lot.id, "%").isEmpty())
            val predictor=MobilePredictor(context)
            val prediction=store.savePrediction(predictor.predict(second.wafer))
            store.savePrediction(prediction.copy(modelSha256="other-test-model"))
            assertEquals(10L,store.lotDetail(lot.id).totalCount)
            val analysis=store.saveAnalysis(second.wafer.id,prediction.id,"test-mock-model","这是自动测试的模拟解析，不是 DeepSeek 真实响应。")
            store.close(); store=LocalWaferStore(context,name)
            assertEquals(prediction.id,store.prediction(second.wafer.id,predictor.modelHash)!!.id)
            assertEquals(analysis.content,store.latestAnalysis(second.wafer.id,prediction.id)!!.content)
            assertEquals(1,store.listWafers(1,1,lot.id).size)
            assertEquals(2,store.detail(second.wafer.id).map[0][0])
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun lowYieldFilteringUsesStrictThresholdAndSortsBeforePaging() {
        val name = "qa-${UUID.randomUUID()}.db"
        val store = LocalWaferStore(context, name)
        try {
            val lowest = store.importJson(json(wafer = "W1", map = "[[2,2,1]]"), "low.json").wafer
            val exact = store.importJson(json(wafer = "W2", map = "[[1,1,1,1,1,1,1,1,1,2]]"), "exact.json").wafer
            val highest = store.importJson(json(wafer = "W3", map = "[[1,1]]"), "high.json").wafer
            store.importJson(json(lot = "OTHER", map = "[[2]]"), "other.json")
            val lot = store.listLots(20, 0).single { it.lotCode == "QA" }

            assertEquals(lowest.id, store.listWafers(1, 0, lot.id, order = WaferOrder.LOWEST).single().id)
            assertEquals(exact.id, store.listWafers(1, 1, lot.id, order = WaferOrder.LOWEST).single().id)
            assertEquals(highest.id, store.listWafers(1, 0, lot.id, order = WaferOrder.HIGHEST).single().id)
            assertEquals(lowest.id, store.listWafers(20, 0, lot.id, belowPercent = 90).single().id)
            assertTrue(store.listWafers(20, 0, lot.id, "EXACT", belowPercent = 90).isEmpty())
            assertEquals(exact.id, store.listWafers(20, 0, lot.id, "EXACT", belowPercent = 95).single().id)
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun yieldBandsMatchOverviewAtBoundariesAndApplyBeforePagingAndCsvExport() {
        val name = "qa-${UUID.randomUUID()}.db"
        val store = LocalWaferStore(context, name)
        fun sample(code: String, good: Int, total: Int = 100): Int {
            val map = (0 until total).joinToString(prefix = "[[", postfix = "]]", separator = ",") {
                if (it < good) "1" else "2"
            }
            return store.importJson(json(wafer = code, map = map), "$code.json").wafer.id
        }
        try {
            val highIds = (0 until 25).map { sample("HIGH-$it", 95 + it % 6) }.toSet()
            val mediumIds = setOf(sample("EXACT80", 80), sample("BELOW95", 94))
            val lowIds = setOf(sample("ZERO", 0), sample("BELOW80", 79))
            val expected = listOf(highIds, mediumIds, lowIds)
            val overview = store.overview()
            assertEquals(expected.map { it.size }, overview.yieldBands)
            WaferYieldBand.entries.forEachIndexed { index, band ->
                val records = store.listWafers(100, 0, null, yieldBand = band)
                assertEquals(expected[index], records.map { it.id }.toSet())
                assertEquals(overview.yieldBands[index], records.size)
            }
            val firstPage = store.listWafers(20, 0, null, yieldBand = WaferYieldBand.HIGH)
            val secondPage = store.listWafers(20, 20, null, yieldBand = WaferYieldBand.HIGH)
            assertEquals(20, firstPage.size)
            assertEquals(5, secondPage.size)
            assertEquals(highIds, (firstPage + secondPage).map { it.id }.toSet())
            assertEquals(80.0, store.listWafers(1, 0, null, order = WaferOrder.LOWEST,
                yieldBand = WaferYieldBand.MEDIUM).single().yieldRate * 100, 0.0)
            assertEquals("EXACT80.json", store.listWafers(20, 0, null, query = "exact80",
                yieldBand = WaferYieldBand.MEDIUM).single().filename)
            assertTrue(store.listWafers(20, 0, null, query = "exact80", yieldBand = WaferYieldBand.HIGH).isEmpty())
            val csv = java.io.StringWriter()
            assertEquals(25, store.writeWafersCsv(csv,
                WaferCsvQuery(null, "", WaferOrder.RECENT, null, WaferYieldBand.HIGH)) {})
            assertFalse(csv.toString().contains("EXACT80"))
            assertTrue(csv.toString().contains("HIGH-0"))
            assertTrue(csv.toString().contains("HIGH-24"))
            assertEquals(29, store.listWafers(100, 0, null).size)
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun csvExportsEntireFilteredQueryAndQuotesSpreadsheetText() {
        val name = "qa-${UUID.randomUUID()}.db"
        val store = LocalWaferStore(context, name)
        try {
            store.importJson(json(lot = "=QA", wafer = "W1", map = "[[2,1]]"), "a,\"test\".json")
            store.importJson(json(lot = "=QA", wafer = "W2", map = "[[1,1]]"), "pass.json")
            val output = java.io.StringWriter()
            val count = store.writeWafersCsv(output, WaferCsvQuery(null, "=QA", WaferOrder.LOWEST, 90)) {}
            assertEquals(1, count)
            val csv = output.toString()
            assertTrue(csv.startsWith("\uFEFF批次,晶圆"))
            assertTrue(csv.contains("\"'=QA\",\"W1\",\"a,\"\"test\"\".json\",1,1,2,50.00,"))
            assertFalse(csv.contains("pass.json"))
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun chatUsesRecentTurnsAndKeepsConversationOnDevice() = runBlocking {
        val suffix = UUID.randomUUID()
        val name = "qa-chat-$suffix.json"
        val profileName = "qa-chat-profile-$suffix"
        val legacyName = "qa-chat-legacy-$suffix"
        val profiles = AiProfileStore(context, profileName, legacyName)
        val store = ChatStore(context, name)
        val server = MockWebServer()
        server.start()
        try {
            profiles.save(null, AiProvider.DEEPSEEK, "模拟", "", "deepseek-flash", "test-key-for-local-chat")
            val profileId = requireNotNull(profiles.read().activeId)
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/chat/completions")).build())
            }.build()
            java.io.File(context.noBackupFilesDir, name).writeText("[]")
            repeat(3) { index ->
                server.enqueue(MockResponse().setBody(
                    """{"choices":[{"finish_reason":"stop","message":{"content":"模拟回复 ${index + 1}"}}]}"""
                ))
            }
            val viewModel = withContext(Dispatchers.Main) { ChatViewModel(context, store, profiles, DeepSeekClient(transport)) }
            withTimeout(5_000) { viewModel.state.first { !it.loading } }
            val wafer = WaferJson.parse(json(lot = "PRIVATE_LOT"), "PRIVATE_NAME.json").detail
            withContext(Dispatchers.Main) {
                viewModel.edit("解释良率 PRIVATE_LOCAL")
                viewModel.send(null)
            }
            val local = withTimeout(5_000) { viewModel.state.first { it.turns.size == 1 } }
            assertEquals("基础助手", local.turns.single().model)
            assertTrue(local.turns.single().reply.contains("良率等于"))
            assertEquals(0, server.requestCount)
            withContext(Dispatchers.Main) {
                viewModel.edit("如何读晶圆图？")
                viewModel.send(ChatPrompt.summarize(wafer, null), profileId)
            }
            withTimeout(5_000) { viewModel.state.first { it.turns.size == 2 } }
            withContext(Dispatchers.Main) {
                viewModel.edit("继续解释良率。")
                viewModel.send(null, profileId)
            }
            withTimeout(5_000) { viewModel.state.first { it.turns.size == 3 } }
            val first = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
            val second = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
            assertEquals(2, first.length())
            assertFalse(first.toString().contains("PRIVATE_LOCAL"))
            assertEquals(4, second.length())
            assertEquals("assistant", second.getJSONObject(2).getString("role"))
            assertEquals("模拟回复 1", second.getJSONObject(2).getString("content"))
            assertTrue(second.toString().contains("有效芯片 5"))
            assertFalse(second.toString().contains("PRIVATE"))
            assertEquals(listOf("模拟回复 1", "模拟回复 2"), ChatStore(context, name).read().drop(1).map { it.reply })
            val original = ChatStore(context, name).readArchive().activeId
            withContext(Dispatchers.Main) { viewModel.newChat() }
            withTimeout(5_000) { viewModel.state.first { !it.busy && it.archive.activeId != original } }
            withContext(Dispatchers.Main) {
                viewModel.edit("这是独立的新对话。")
                viewModel.send(null, profileId)
            }
            withTimeout(5_000) { viewModel.state.first { !it.busy && it.turns.size == 1 } }
            val third = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
            assertEquals(2, third.length())
            assertFalse(third.toString().contains("模拟回复 2"))
            assertEquals(2, ChatStore(context, name).readArchive().conversations.size)
            withContext(Dispatchers.Main) { viewModel.openChat(original) }
            withTimeout(5_000) { viewModel.state.first { !it.busy && it.archive.activeId == original && it.turns.size == 3 } }
            withContext(Dispatchers.Main) { viewModel.clear() }
            withTimeout(5_000) { viewModel.state.first { !it.busy && it.turns.isEmpty() } }
            assertTrue(ChatStore(context, name).read().isEmpty())
        } finally {
            server.shutdown()
            java.io.File(context.noBackupFilesDir, name).delete()
            context.deleteSharedPreferences(profileName)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("$legacyName.aes") }
        }
    }

    @Test fun backupRestoresWaferPredictionAndAnalysisAndRollsBackInvalidInput() {
        val sourceName = "qa-source-${UUID.randomUUID()}.db"
        val targetName = "qa-target-${UUID.randomUUID()}.db"
        val source = LocalWaferStore(context, sourceName)
        val target = LocalWaferStore(context, targetName)
        try {
            val wafer = source.importJson(json(lot = "RESTORE"), "source.json").wafer
            val prediction = source.savePrediction(MobilePredictor(context).predict(wafer))
            source.saveAnalysis(wafer.id, prediction.id, "fixture", "backup verification")
            val before = target.importJson(json(lot = "ORIGINAL"), "original.json").wafer
            val output = java.io.StringWriter()
            assertEquals(1, source.backup(output) {})
            val backup = output.toString()
            assertThrows(Exception::class.java) { target.restoreBackup(backup.dropLast(4).reader()) {} }
            assertEquals("ORIGINAL", target.detail(before.id).lotCode)
            assertEquals(1, target.restoreBackup(backup.reader()) {})
            assertEquals(wafer, target.detail(wafer.id))
            assertEquals(prediction, target.prediction(wafer.id))
            assertEquals("backup verification", target.latestAnalysis(wafer.id, prediction.id)?.content)
        } finally {
            source.close()
            target.close()
            context.deleteDatabase(sourceName)
            context.deleteDatabase(targetName)
        }
    }

    @Test fun keyIsEncryptedAndCanBeRemoved() {
        val name="qa-ai-${UUID.randomUUID()}"; val settings=DeepSeekSettings(context,name)
        try {
            settings.selectModel("deepseek-v4-pro")
            assertEquals("deepseek-v4-pro", DeepSeekSettings(context, name).model)
            assertFalse(settings.hasKey)
            val dummy="not-a-real-api-key-for-testing"
            settings.save(dummy,"deepseek-flash")
            assertEquals(dummy,settings.key())
            val values=context.getSharedPreferences(name,0).all
            assertFalse(values.values.any { it.toString().contains(dummy) })
            settings.selectModel("deepseek-v4-pro")
            val reopened = DeepSeekSettings(context, name)
            assertEquals("deepseek-v4-pro", reopened.model)
            assertEquals(dummy, reopened.key())
            settings.clear()
            assertFalse(settings.hasKey)
            assertEquals("deepseek-v4-pro", settings.model)
        } finally {
            context.getSharedPreferences(name,0).edit().clear().commit()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("$name.aes") }
        }
    }

    @Test fun deepSeekRequestUsesSummaryAndParsesResponse() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"自动测试模拟解析，不是真实服务输出。\"}}]}"))
            val wafer=WaferJson.parse(json(lot="PRIVATE_LOT"),"PRIVATE_FILENAME.json").detail.copy(id=7)
            val prediction=MobilePredictor(context).predict(wafer).copy(id=5)
            val evidence=AnalysisEvidence.create(wafer,prediction)
            assertFalse(evidence.toString().contains("PRIVATE")); assertFalse(evidence.has("map"))
            assertEquals(5,evidence.getInt("effective_dies"))
            val result=DeepSeekClient(endpoint=server.url("/chat/completions").toString()).analyze("dummy-test-key","deepseek-flash",evidence)
            assertTrue(result.contains("模拟解析"))
            val request=server.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("POST",request.method); assertEquals("Bearer dummy-test-key",request.getHeader("Authorization"))
            val payload=JSONObject(request.body.readUtf8())
            assertEquals("deepseek-flash",payload.getString("model")); assertFalse(payload.getBoolean("stream"))
            assertEquals("disabled",payload.getJSONObject("thinking").getString("type"))
            server.enqueue(MockResponse().setBody("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"测试模型响应\"}}]}"))
            DeepSeekClient().analyze("test-key", "test-model", evidence, server.url("/v1/chat/completions").toString(), AiProvider.OPENAI)
            val otherRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
            val otherPayload = JSONObject(otherRequest.body.readUtf8())
            assertEquals("/v1/chat/completions", otherRequest.path)
            assertEquals("test-model", otherPayload.getString("model"))
            assertTrue(otherPayload.has("max_completion_tokens"))
            assertFalse(otherPayload.has("thinking"))
        } finally { server.shutdown() }
    }

    @Test fun profileMigrationKeepsCredentialsAndIsolatesProviders() {
        val suffix = UUID.randomUUID()
        val oldName = "qa-legacy-$suffix"
        val newName = "qa-profiles-$suffix"
        try {
            val legacy = DeepSeekSettings(context, oldName)
            legacy.save("test-legacy-key-for-qa", "deepseek-v4-pro")
            val store = AiProfileStore(context, newName, oldName)
            val oldProfile = store.read().active!!
            assertEquals("deepseek-v4-pro", oldProfile.model)
            assertEquals("test-legacy-key-for-qa", store.request().key)
            assertFalse(legacy.hasKey)
            store.save(null, AiProvider.CUSTOM, "测试服务", "https://example.org/v1/", "test-model", "test-custom-key-for-qa")
            val custom = store.read().profiles.single { it.provider == AiProvider.CUSTOM }
            store.select(custom.id)
            val reopened = AiProfileStore(context, newName, oldName)
            assertEquals("test-custom-key-for-qa", reopened.request().key)
            assertEquals("https://example.org/v1/chat/completions", reopened.request().profile.endpoint)
            try {
                store.save(custom.id, AiProvider.CUSTOM, "新接口", "https://different.example/v1", "test-model", "")
                fail("Changing the address must require a new key")
            } catch (_: IllegalArgumentException) { }
            val restored = reopened.restoreDefaultSelection()
            assertEquals(oldProfile.id, restored.activeId)
            assertEquals(2, restored.profiles.size)
            reopened.select(custom.id)
            assertEquals("test-custom-key-for-qa", reopened.request().key)
            store.remove(custom.id)
            assertEquals(oldProfile.id, store.read().activeId)
            assertEquals("test-legacy-key-for-qa", store.request().key)
        } finally {
            context.getSharedPreferences(oldName, 0).edit().clear().commit()
            context.getSharedPreferences(newName, 0).edit().clear().commit()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("$oldName.aes") }
        }
    }

    @Test fun deepSeekErrorsAndCancellationAreHandled() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            val client=DeepSeekClient(endpoint=server.url("/chat/completions").toString())
            for(code in listOf(401,402,429,500)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("not exposed to the UI"))
                try { client.analyze("dummy","deepseek-flash",JSONObject()); fail("Expected API error") }
                catch(error: IllegalStateException) { assertFalse(error.message.orEmpty().contains("not exposed")) }
            }
            server.enqueue(MockResponse().setBody("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}"))
            try { client.analyze("dummy","deepseek-flash",JSONObject()); fail("Expected incomplete result rejection") } catch(_: IllegalStateException) { }
            server.enqueue(MockResponse().setBodyDelay(5,TimeUnit.SECONDS).setBody("{}"))
            val pending=async { client.analyze("dummy","deepseek-flash",JSONObject()) }
            delay(100); pending.cancelAndJoin(); assertTrue(pending.isCancelled)
        } finally { server.shutdown() }
    }
}
