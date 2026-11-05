package com.example.waferai

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waferai.data.ai.*
import com.example.waferai.data.analysis.WaferMapFilter
import com.example.waferai.data.remote.WaferDetail
import com.example.waferai.ui.chat.*
import com.example.waferai.ui.importdata.*
import com.example.waferai.ui.theme.WaferAITheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class PerformanceUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun maximumMatrixIsPreparedOffMainAndRemainsSelectableAfterPinch() {
        val mainReads = AtomicInteger()
        val workerReads = AtomicInteger()
        val bins = List(512) { row -> List(512) { col -> if (row in 255..256 && col in 255..256) 2 else 1 } }
        val tracked = object : AbstractList<List<Int>>() {
            override val size = 512
            override fun get(index: Int): List<Int> {
                if (Looper.myLooper() == Looper.getMainLooper()) mainReads.incrementAndGet() else workerReads.incrementAndGet()
                return bins[index]
            }
        }
        val wafer = WaferDetail(81,"QA","W","max",512,512,262140,4,262144,262140.0/262144,tracked)
        compose.setContent { WaferAITheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { WaferExplorer(wafer, WaferMapFilter.ALL) {} }
        } }
        val canvas = compose.onNodeWithContentDescription("晶圆图，512 行 512 列。可双指缩放，放大后拖动，点击查看芯片状态。")
        compose.waitUntil(10000) {
            canvas.fetchSemanticsNode().config.getOrElse(SemanticsProperties.StateDescription) { "" } == "绘制完成"
        }
        assertTrue("Raster and failure preparation must run on workers", workerReads.get() >= 1024)
        assertTrue("No full matrix scan on main", mainReads.get() < 20)
        canvas.performTouchInput { pinch(center-Offset(width*.1f,0f),center-Offset(width*.3f,0f),center+Offset(width*.1f,0f),center+Offset(width*.3f,0f)) }
        canvas.performTouchInput { click(center) }
        compose.onNodeWithText("· 不合格", substring = true).assertExists()
        assertTrue("Gesture must not rescan matrix", mainReads.get() < 30)
        compose.onNodeWithText("还原视图").performScrollTo().performClick()
        compose.onNodeWithText("100%").assertExists()
    }

    @Test fun replacingMatrixWithSameRecordIdCannotKeepOldRaster() {
        fun wafer(bin: Int) = WaferDetail(9,"Q","W","same",2,2,if(bin==1)4 else 0,if(bin==2)4 else 0,4,if(bin==1)1.0 else 0.0,List(2){List(2){bin}})
        val selection = mutableStateOf(wafer(1))
        var image: ImageBitmap? = null
        compose.setContent { WaferAITheme {
            val pixels = rememberWaferImage(selection.value)
            SideEffect { image = pixels }
        } }
        compose.waitUntil(5000) { image != null }
        val green = image!!.toPixelMap()[0,0]
        compose.runOnIdle { selection.value = wafer(2) }
        compose.waitUntil(5000) { image != null && image!!.toPixelMap()[0,0] != green }
        val red = image!!.toPixelMap()[0,0]
        assertTrue(red.red > red.green)
    }

    @Test fun thirtyLongTurnsScrollAndTypingDoesNotLoseHistoryPosition() {
        val name = "qa-performance-${UUID.randomUUID()}"
        val directory = File(context.cacheDir,name).apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = directory
            override fun getSharedPreferences(key: String, mode: Int) = context.getSharedPreferences("$name-$key",mode)
        }
        try {
            val store = ChatStore(isolated)
            store.writeArchive(ChatArchive().updateActive(List(30) { ChatTurn("问题 $it", "解释段落。".repeat(700), "QA") }))
            val model = ChatViewModel(isolated,store,AiProfileStore(isolated))
            compose.waitUntil(5000) { !model.state.value.loading }
            assertNull(model.state.value.error)
            val ids = model.state.value.turns.map { it.id }
            compose.setContent { WaferAITheme { ChatScreen(model, AiProfilesState(emptyList(),null),null,false,null,null,
                {},{},{},{},{},{},ChatThinking.DEFAULT,{},true,{},null) } }
            compose.onNodeWithTag("chat-transcript").performScrollToIndex(0)
            compose.onNode(hasText("问题 0") and hasAnyAncestor(hasTestTag("chat-transcript"))).assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextInput("继续分析")
            assertEquals("继续分析", model.state.value.draft)
            assertEquals(ids, model.state.value.turns.map { it.id })
            compose.onNode(hasText("问题 0") and hasAnyAncestor(hasTestTag("chat-transcript"))).assertIsDisplayed()
            compose.onNodeWithTag("chat-transcript").performScrollToIndex(29)
            compose.onNode(hasText("问题 29") and hasAnyAncestor(hasTestTag("chat-transcript"))).assertExists()
        } finally {
            directory.deleteRecursively()
            context.deleteSharedPreferences("$name-ai_profiles")
            context.deleteSharedPreferences("$name-chat_web_search")
        }
    }
}
