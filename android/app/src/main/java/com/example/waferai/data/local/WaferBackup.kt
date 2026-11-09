package com.example.waferai.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.example.waferai.data.remote.WaferPrediction
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.Reader
import java.io.Writer

internal object WaferBackup {
    private const val Format = "WaferAI-data"
    private const val Version = 3
    private val gson = Gson()

    private data class Table(val name: String, val numbers: List<String>, val text: List<String>) {
        val columns get() = numbers + text
    }

    private val tables = listOf(
        Table("lots", listOf("id"), listOf("lot_code")),
        Table("wafers", listOf("id", "lot_id", "rows", "cols", "good_count", "bad_count"),
            listOf("wafer_code", "filename", "map_json", "input_hash", "created_at", "source_code")),
        Table("predictions", listOf("id", "wafer_id"), listOf("model_hash", "result_json")),
        Table("analyses", listOf("id", "wafer_id", "prediction_id"), listOf("model", "content", "created_at")),
        Table("inspections", listOf("id", "prediction_id", "threshold"), listOf("created_at"))
    )

    fun write(db: SQLiteDatabase, output: Writer, checkActive: () -> Unit): Int {
        val json = JsonWriter(output)
        var waferCount = 0
        db.beginTransactionNonExclusive()
        try {
            json.beginObject().name("format").value(Format).name("version").value(Version.toLong())
            for (table in tables) {
                json.name(table.name).beginArray()
                db.query(table.name, table.columns.toTypedArray(), null, null, null, null, "id").use { cursor ->
                    while (cursor.moveToNext()) {
                        checkActive()
                        json.beginArray()
                        table.numbers.indices.forEach { json.value(cursor.getLong(it)) }
                        table.text.indices.forEach { json.value(cursor.getString(table.numbers.size + it)) }
                        json.endArray()
                        if (table.name == "wafers") waferCount++
                    }
                }
                json.endArray()
            }
            json.endObject().flush()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return waferCount
    }

    fun restore(db: SQLiteDatabase, input: Reader, checkActive: () -> Unit): Int {
        val json = JsonReader(input)
        json.beginObject()
        require(json.nextName() == "format" && json.nextString() == Format)
        require(json.nextName() == "version")
        val version = json.nextInt()
        require(version in 1..Version)
        var waferCount = 0
        db.beginTransaction()
        try {
            tables.asReversed().forEach { db.delete(it.name, null, null) }
            for (table in if (version == 1) tables.dropLast(1) else tables) {
                require(json.nextName() == table.name)
                json.beginArray()
                while (json.hasNext()) {
                    checkActive()
                    json.beginArray()
                    val values = ContentValues()
                    table.numbers.forEach { column ->
                        require(json.peek() == JsonToken.NUMBER)
                        val number = json.nextLong()
                        require(number in 0..Int.MAX_VALUE.toLong())
                        if (column == "id" || column.endsWith("_id")) require(number > 0)
                        values.put(column, number.toInt())
                    }
                    val textColumns = if (version < 3 && table.name == "wafers") table.text.dropLast(1) else table.text
                    textColumns.forEach { column ->
                        require(json.peek() == JsonToken.STRING)
                        values.put(column, json.nextString())
                    }
                    json.endArray()
                    validate(db, table.name, values)
                    db.insertOrThrow(table.name, null, values)
                    if (table.name == "wafers") waferCount++
                }
                json.endArray()
            }
            json.endObject()
            require(json.peek() == JsonToken.END_DOCUMENT)
            checkActive()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return waferCount
    }

    private fun validate(db: SQLiteDatabase, table: String, values: ContentValues) {
        when (table) {
            "lots" -> require(values.getAsString("lot_code").validCode())
            "wafers" -> {
                val code = values.getAsString("wafer_code")
                require(code.validCode())
                val original = values.getAsString("map_json")
                val filename = values.getAsString("filename")
                require(filename.length <= 180)
                values.getAsString("source_code")?.let { require(WaferSource.fromCode(it).name == it) }
                val parsed = WaferJson.parse(
                    """{"lot_code":"backup","wafer_code":${gson.toJson(code)},"map":$original}""".toByteArray(),
                    filename
                )
                val wafer = parsed.detail
                require(wafer.rows == values.getAsInteger("rows") && wafer.cols == values.getAsInteger("cols"))
                require(wafer.goodCount == values.getAsInteger("good_count") && wafer.badCount == values.getAsInteger("bad_count"))
                require(parsed.inputHash == values.getAsString("input_hash"))
            }
            "predictions" -> {
                val prediction = gson.fromJson(values.getAsString("result_json"), WaferPrediction::class.java)
                require(prediction.waferId == values.getAsInteger("wafer_id"))
                require(prediction.modelSha256 == values.getAsString("model_hash"))
                require(prediction.label.isNotBlank() && prediction.labelZh.isNotBlank() && prediction.scores.isNotEmpty())
            }
            "analyses" -> db.rawQuery(
                "SELECT 1 FROM predictions WHERE id=? AND wafer_id=?",
                arrayOf(values.getAsString("prediction_id"), values.getAsString("wafer_id"))
            ).use { require(it.moveToFirst()) }
            "inspections" -> {
                require(values.getAsInteger("threshold") in 1..100)
                db.rawQuery("SELECT 1 FROM predictions WHERE id=? AND wafer_id=?",
                    arrayOf(values.getAsString("prediction_id"), values.getAsString("id"))
                ).use { require(it.moveToFirst()) }
            }
        }
        values.getAsString("created_at")?.let { java.time.Instant.parse(it) }
    }

    private fun String.validCode() = isNotBlank() && trim() == this &&
        codePointCount(0, length) <= 64 && none(Char::isISOControl)
}
