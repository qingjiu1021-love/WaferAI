package com.example.waferai.data.local

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant

internal enum class LogOperation(val label: String) {
    IMPORT("晶圆导入"), IMAGE("图片解析"), CLASSIFY("本机识别"),
    REPORT("PDF 导出"), KNOWLEDGE("知识库同步"), TRAIN("手机模型训练")
}

internal data class LogEntry(val time: String, val operation: LogOperation, val success: Boolean)

internal class OperationLog(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "wafer-operations.json"))
    private val mutable = MutableStateFlow(read())
    val entries = mutable.asStateFlow()

    @Synchronized fun record(operation: LogOperation, success: Boolean) {
        val next = (listOf(LogEntry(Instant.now().toString(), operation, success)) + mutable.value).take(150)
        // An unavailable log must not make a completed import or prediction appear to fail.
        runCatching { write(next) }
        mutable.value = next
    }

    @Synchronized fun clear() {
        write(emptyList())
        mutable.value = emptyList()
    }

    private fun read(): List<LogEntry> = try {
        val json = JSONArray(file.readFully().toString(Charsets.UTF_8))
        List(minOf(json.length(), 150)) { index ->
            val item = json.getJSONObject(index)
            LogEntry(item.getString("time"), LogOperation.valueOf(item.getString("operation")), item.getBoolean("success"))
        }
    } catch (_: FileNotFoundException) {
        emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun write(entries: List<LogEntry>) {
        val json = JSONArray(entries.map {
            JSONObject().put("time", it.time).put("operation", it.operation.name).put("success", it.success)
        })
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray())
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}
