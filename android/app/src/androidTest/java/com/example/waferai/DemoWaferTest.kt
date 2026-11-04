package com.example.waferai

import android.content.ContextWrapper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waferai.data.ai.*
import com.example.waferai.data.local.*
import com.example.waferai.ui.importdata.DemoWaferPicker
import com.example.waferai.ui.theme.WaferAITheme
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DemoWaferTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun packagedSamplesImportOfflineWithoutDuplicatingRecords() {
        val name = "qa-demo-${UUID.randomUUID()}.db"
        val store = LocalWaferStore(context, name)
        try {
            val real = DemoWafer.entries.filter { it.isReal }
            for (sample in real) {
                val wafer = store.importJson(context.assets.open("samples/${sample.filename}").use { it.readBytes() }, sample.filename).wafer
                assertEquals(sample.goodCount, wafer.goodCount)
                assertEquals(sample.badCount, wafer.badCount)
            }
            assertEquals(27, store.listWafers(100, 0, null).size)
            val sample = real.last()
            store.importJson(context.assets.open("samples/${sample.filename}").use { it.readBytes() }, sample.filename)
            assertEquals(27, store.listWafers(100, 0, null).size)
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun newKnowledgeIsAvailableOfflineAcrossReloadsAndPreservesExistingCache() {
        val root = File(context.cacheDir, "qa-knowledge-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        val cache = File(root, "wafer-knowledge.json")
        cache.writeText("""{"synced_at":1,"entries":[{"source":"https://example.org/saved","title":"QA saved","body":"Existing knowledge"}]}""")
        try {
            assertEquals(44, BuiltInKnowledge.size)
            repeat(2) {
                val knowledge = WaferKnowledge(isolated, OperationLog(isolated))
                val selected = knowledge.search("中心型 02").first()
                assertTrue(selected.body.contains("2177"))
                assertTrue(selected.body.contains("283"))
                assertTrue(knowledge.search("none").any { it.body.contains("不等于所有芯片合格") })
                assertEquals("Existing knowledge", knowledge.search("QA saved").single().body)
                assertEquals(1, knowledge.state.value.online.size)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun pickerCanSearchAndChooseTheLastRealSample() {
        var chosen: DemoWafer? = null
        compose.setContent { WaferAITheme { DemoWaferPicker(false, { chosen = it }, {}) } }
        compose.onNodeWithTag("demo-search").performTextInput("none")
        compose.onNodeWithText("找到 3 个样本。原始标注不等于 AI 预测。").assertExists()
        compose.onNodeWithTag("demo-samples").performScrollToNode(hasTestTag("wm811k-real-none-03.json"))
        compose.onNodeWithTag("wm811k-real-none-03.json").performClick()
        compose.runOnIdle { assertEquals(DemoWafer.WM_NONE_3, chosen) }
        compose.onNodeWithTag("demo-search").performTextClearance()
        compose.onNodeWithTag("demo-search").performTextInput("does-not-exist")
        compose.onNodeWithText("没有匹配样本，试试其他类别或编号。").assertExists()
    }
}
