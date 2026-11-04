package com.example.waferai

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waferai.data.local.WaferYieldBand
import com.example.waferai.data.repository.ImportRepository
import com.example.waferai.ui.history.HistoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class HistoryViewModelTest {
    @Test fun reopeningHistoryRetainsLoadedPagesAndTheLastPageHasNoExtraLoadButton() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "qa-history-${UUID.randomUUID()}"
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), prefix).toFile()
        val isolated = object : ContextWrapper(context) {
            override fun getDatabasePath(name: String) = context.getDatabasePath("$prefix-$name")
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                context.openOrCreateDatabase("$prefix-$name", mode, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?) =
                context.openOrCreateDatabase("$prefix-$name", mode, factory, errorHandler)
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$prefix-$name", mode)
            override fun getNoBackupFilesDir(): File = directory
        }
        val repository = ImportRepository(isolated)
        val owner = ViewModelStore()
        try {
            repeat(45) { index ->
                val map = List(20) { if (it < if (index < 25) 19 else 10) 1 else 2 }
                val input = """{"lot_code":"QA","wafer_code":"W$index","map":[${map.joinToString(prefix = "[", postfix = "]")}]}"""
                repository.store.importJson(input.toByteArray(), "wafer-$index.json")
            }
            val model = withContext(Dispatchers.Main) { HistoryViewModel(repository).also { owner.put("history", it) } }
            suspend fun loaded() = withTimeout(5000) { model.state.first { !it.loading } }
            withContext(Dispatchers.Main) { model.show(null, false, 90) }
            assertEquals(20, loaded().records.size)
            withContext(Dispatchers.Main) { model.loadMore() }
            assertEquals(40, loaded().records.size)
            withContext(Dispatchers.Main) { model.show(null, false, 90) }
            assertEquals(40, model.state.value.records.size)
            assertEquals(40, loaded().records.size)
            withContext(Dispatchers.Main) { model.loadMore() }
            assertEquals(45, loaded().records.size)
            assertFalse(loaded().hasMore)
            withContext(Dispatchers.Main) { model.refresh() }
            assertEquals(45, loaded().records.size)
            assertFalse(loaded().hasMore)
            withContext(Dispatchers.Main) { model.show(null, false, 90, WaferYieldBand.HIGH) }
            assertEquals(20, loaded().records.size)
            assertTrue(loaded().hasMore)
            withContext(Dispatchers.Main) { model.loadMore() }
            assertEquals(25, loaded().records.size)
            assertFalse(loaded().hasMore)
            withContext(Dispatchers.Main) { model.show(null, false, 90, WaferYieldBand.HIGH) }
            assertEquals(25, loaded().records.size)
        } finally {
            withContext(Dispatchers.Main) { owner.clear() }
            repository.store.close()
            context.deleteDatabase("$prefix-waferai-local.db")
            context.deleteSharedPreferences("$prefix-ai_profiles")
            context.deleteSharedPreferences("$prefix-deepseek_credentials")
            directory.deleteRecursively()
        }
    }
}
