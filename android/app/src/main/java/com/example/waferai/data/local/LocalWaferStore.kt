package com.example.waferai.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.waferai.data.remote.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Writer
import java.io.Reader
import java.time.Instant
import java.util.Locale

data class AiAnalysis(val id: Int, val waferId: Int, val predictionId: Int, val model: String, val content: String, val createdAt: String)

enum class WaferOrder(val label: String) {
    RECENT("最近导入"), LOWEST("良率从低到高"), HIGHEST("良率从高到低")
}

enum class WaferYieldBand(val label: String, internal val condition: String) {
    HIGH("95% 及以上", "100*good_count >= 95*(good_count+bad_count)"),
    MEDIUM("80% 至 95% 以下", "100*good_count >= 80*(good_count+bad_count) AND 100*good_count < 95*(good_count+bad_count)"),
    LOW("低于 80%", "100*good_count < 80*(good_count+bad_count)")
}

data class WaferCsvQuery(
    val lotId: Int?, val text: String, val order: WaferOrder, val belowPercent: Int?,
    val yieldBand: WaferYieldBand? = null
)

data class WaferOverview(
    val wafers: Int,
    val lots: Int,
    val good: Long,
    val bad: Long,
    val yieldBands: List<Int>,
    val recent: List<WaferRecord>
) {
    val total: Long get() = good + bad
    val yieldRate: Double? get() = if (total == 0L) null else good.toDouble() / total
}

class LocalWaferStore(context: Context, name: String = "waferai-local.db") : SQLiteOpenHelper(context, name, null, 2) {
    private val gson = Gson()
    private val revision = MutableStateFlow(0L)
    internal val waferRevision = revision.asStateFlow()
    @Synchronized fun backup(writer: Writer, checkActive: () -> Unit) = WaferBackup.write(writableDatabase, writer, checkActive)
    @Synchronized fun restoreBackup(reader: Reader, checkActive: () -> Unit) =
        WaferBackup.restore(writableDatabase, reader, checkActive).also { revision.value++ }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE lots(id INTEGER PRIMARY KEY, lot_code TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE wafers(id INTEGER PRIMARY KEY, lot_id INTEGER NOT NULL REFERENCES lots(id), wafer_code TEXT NOT NULL, filename TEXT NOT NULL, rows INTEGER NOT NULL, cols INTEGER NOT NULL, good_count INTEGER NOT NULL CHECK(good_count>=0), bad_count INTEGER NOT NULL CHECK(bad_count>=0), map_json TEXT NOT NULL, input_hash TEXT NOT NULL, created_at TEXT NOT NULL, UNIQUE(lot_id,wafer_code))")
        db.execSQL("CREATE INDEX ix_wafers_lot ON wafers(lot_id,id)")
        db.execSQL("CREATE TABLE predictions(id INTEGER PRIMARY KEY, wafer_id INTEGER NOT NULL REFERENCES wafers(id), model_hash TEXT NOT NULL, result_json TEXT NOT NULL, UNIQUE(wafer_id,model_hash))")
        db.execSQL("CREATE TABLE analyses(id INTEGER PRIMARY KEY, wafer_id INTEGER NOT NULL REFERENCES wafers(id), prediction_id INTEGER NOT NULL REFERENCES predictions(id), model TEXT NOT NULL, content TEXT NOT NULL, created_at TEXT NOT NULL)")
        db.execSQL("CREATE INDEX ix_analyses_wafer ON analyses(wafer_id,id)")
        createInspections(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2)
        createInspections(db)
    }
    private fun createInspections(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE inspections(id INTEGER PRIMARY KEY REFERENCES wafers(id), " +
                "prediction_id INTEGER NOT NULL REFERENCES predictions(id), " +
                "threshold INTEGER NOT NULL CHECK(threshold BETWEEN 1 AND 100), created_at TEXT NOT NULL)"
        )
    }
    fun importJson(bytes: ByteArray, filename: String): ImportResponse =
        saveParsed(WaferJson.parse(bytes, filename))

    fun importCsv(bytes: ByteArray, filename: String): ImportResponse =
        saveParsed(WaferCoordinateCsv.parse(bytes, filename))

    @Synchronized internal fun saveParsed(parsed: ParsedWafer): ImportResponse {
        val wafer = parsed.detail
        val db = writableDatabase
        var inserted = false
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO lots(lot_code) VALUES(?)", arrayOf(wafer.lotCode))
            val lotId = db.rawQuery("SELECT id FROM lots WHERE lot_code=?", arrayOf(wafer.lotCode)).use { it.moveToFirst(); it.getInt(0) }
            val existing = db.rawQuery("SELECT id,input_hash FROM wafers WHERE lot_id=? AND wafer_code=?", arrayOf(lotId.toString(), wafer.waferCode)).use {
                if (it.moveToFirst()) it.getInt(0) to it.getString(1) else null
            }
            if (existing != null) {
                require(existing.second == parsed.inputHash) { "同一批次和晶圆编号已存在不同矩阵，请使用新的晶圆编号。" }
                val result = ImportResponse(true, detail(existing.first)); db.setTransactionSuccessful(); return result
            }
            val values = ContentValues().apply {
                put("lot_id", lotId); put("wafer_code", wafer.waferCode); put("filename", wafer.filename)
                put("rows", wafer.rows); put("cols", wafer.cols); put("good_count", wafer.goodCount); put("bad_count", wafer.badCount)
                put("map_json", gson.toJson(wafer.map)); put("input_hash", parsed.inputHash); put("created_at", Instant.now().toString())
            }
            val id = db.insertOrThrow("wafers", null, values).toInt()
            db.setTransactionSuccessful()
            inserted = true
            return ImportResponse(false, wafer.copy(id = id))
        } finally {
            db.endTransaction()
            if (inserted) revision.value++
        }
    }
    @Synchronized fun detail(id: Int): WaferDetail = readableDatabase.rawQuery(
        "SELECT w.*,l.lot_code FROM wafers w JOIN lots l ON l.id=w.lot_id WHERE w.id=?", arrayOf(id.toString())
    ).use { c ->
        require(c.moveToFirst()) { "手机上没有找到这张晶圆。" }
        val good = c.int("good_count"); val bad = c.int("bad_count")
        @Suppress("UNCHECKED_CAST")
        val map = gson.fromJson(c.text("map_json"), Array<IntArray>::class.java).map { it.toList() }
        WaferDetail(c.int("id"), c.text("lot_code"), c.text("wafer_code"), c.text("filename"), c.int("rows"), c.int("cols"), good,bad,good+bad,good.toDouble()/(good+bad),map)
    }
    @Synchronized fun overview(): WaferOverview = readableDatabase.rawQuery(
        """
        SELECT COUNT(*), COUNT(DISTINCT lot_id), COALESCE(SUM(good_count),0), COALESCE(SUM(bad_count),0),
               ${WaferYieldBand.entries.joinToString(", ") { "SUM(CASE WHEN ${it.condition} THEN 1 ELSE 0 END)" }}
        FROM wafers
        """.trimIndent(), null
    ).use { cursor ->
        check(cursor.moveToFirst())
        WaferOverview(
            cursor.getInt(0), cursor.getInt(1), cursor.getLong(2), cursor.getLong(3),
            listOf(cursor.getInt(4), cursor.getInt(5), cursor.getInt(6)), listWafers(4, 0, null)
        )
    }

    @Synchronized fun listWafers(
        limit: Int, offset: Int, lotId: Int?, query: String = "",
        order: WaferOrder = WaferOrder.RECENT, belowPercent: Int? = null,
        yieldBand: WaferYieldBand? = null
    ): List<WaferRecord> = queryWafers(lotId, query, order, belowPercent, yieldBand, limit, offset).use { c ->
        buildList { while(c.moveToNext()) { val total = c.int("good_count") + c.int("bad_count"); add(WaferRecord(c.int("id"),c.text("lot_code"),c.text("wafer_code"),c.text("filename"),total,c.int("good_count").toDouble()/total,c.text("created_at"))) } }
    }

    @Synchronized fun writeWafersCsv(writer: Writer, query: WaferCsvQuery, checkCancelled: () -> Unit): Int {
        writer.write("\uFEFF批次,晶圆,来源文件,合格芯片,不合格芯片,有效芯片,良率(%),导入时间(UTC)\r\n")
        var count = 0
        queryWafers(query.lotId, query.text, query.order, query.belowPercent, query.yieldBand).use { cursor ->
            while (cursor.moveToNext()) {
                checkCancelled()
                val good = cursor.int("good_count")
                val bad = cursor.int("bad_count")
                val total = good + bad
                val rate = String.format(Locale.ROOT, "%.2f", 100.0 * good / total)
                writer.write(listOf(
                    csvText(cursor.text("lot_code")), csvText(cursor.text("wafer_code")), csvText(cursor.text("filename")),
                    good.toString(), bad.toString(), total.toString(), rate, csvText(cursor.text("created_at"))
                ).joinToString(","))
                writer.write("\r\n")
                count++
            }
        }
        return count
    }

    private fun csvText(value: String): String {
        val first = value.trimStart { it.isWhitespace() || it.isISOControl() }.firstOrNull()
        val formula = first != null && first in "=+-@＝＋－＠"
        val control = value.firstOrNull()?.let { it in "\t\r\n" } == true
        val literal = if (formula || control) "'$value" else value
        return "\"${literal.replace("\"", "\"\"")}\""
    }

    private fun queryWafers(
        lotId: Int?, query: String, order: WaferOrder, belowPercent: Int?, yieldBand: WaferYieldBand?,
        limit: Int? = null, offset: Int = 0
    ): Cursor {
        val filters = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (lotId != null) {
            filters += "w.lot_id=?"
            args += lotId.toString()
        }
        if (query.isNotBlank()) {
            filters += "(instr(lower(l.lot_code),lower(?))>0 OR instr(lower(w.wafer_code),lower(?))>0 OR instr(lower(w.filename),lower(?))>0)"
            repeat(3) { args += query.trim() }
        }
        if (belowPercent != null) {
            require(belowPercent in 1..100)
            filters += "100*w.good_count < CAST(? AS INTEGER)*(w.good_count+w.bad_count)"
            args += belowPercent.toString()
        }
        yieldBand?.let { filters += "(${it.condition})" }
        val yieldExpression = "1.0*w.good_count/(w.good_count+w.bad_count)"
        val sort = when (order) {
            WaferOrder.RECENT -> "w.id DESC"
            WaferOrder.LOWEST -> "$yieldExpression ASC, w.id DESC"
            WaferOrder.HIGHEST -> "$yieldExpression DESC, w.id DESC"
        }
        val where = if (filters.isEmpty()) "" else "WHERE ${filters.joinToString(" AND ")}"
        val paging = if (limit == null) "" else {
            args += listOf(limit.toString(), offset.toString())
            " LIMIT ? OFFSET ?"
        }
        return readableDatabase.rawQuery("SELECT w.id,l.lot_code,w.wafer_code,w.filename,w.good_count,w.bad_count,w.created_at FROM wafers w JOIN lots l ON l.id=w.lot_id $where ORDER BY $sort$paging", args.toTypedArray())
    }
    private fun lots(where: String, args: Array<String>): List<LotSummary> = readableDatabase.rawQuery(
        "SELECT l.id,l.lot_code,COUNT(w.id) AS count,COALESCE(SUM(w.good_count),0) AS good,COALESCE(SUM(w.bad_count),0) AS bad,MAX(w.created_at) AS latest FROM lots l LEFT JOIN wafers w ON w.lot_id=l.id $where GROUP BY l.id,l.lot_code ORDER BY MAX(w.id) DESC,l.id DESC",args).use { c ->
        buildList { while(c.moveToNext()) { val good=c.getLong(3); val bad=c.getLong(4); add(LotSummary(c.getInt(0),c.getString(1),c.getInt(2),good,bad,good+bad,if(good+bad>0) good.toDouble()/(good+bad) else null,c.getString(5))) } }
    }
    @Synchronized fun listLots(limit: Int, offset: Int) = lots("", emptyArray()).drop(offset).take(limit)
    @Synchronized fun lotDetail(id: Int) = lots("WHERE l.id=?", arrayOf(id.toString())).firstOrNull() ?: error("没有找到这个本地批次。")
    @Synchronized fun prediction(waferId: Int, modelHash: String? = null): WaferPrediction? {
        val clause = if(modelHash == null) "" else "AND model_hash=?"
        val args = if(modelHash == null) arrayOf(waferId.toString()) else arrayOf(waferId.toString(),modelHash)
        return readableDatabase.rawQuery("SELECT id,result_json FROM predictions WHERE wafer_id=? $clause ORDER BY id DESC LIMIT 1",args).use {
            if(it.moveToFirst()) gson.fromJson(it.getString(1),WaferPrediction::class.java).copy(id=it.getInt(0)) else null
        }
    }
    @Synchronized fun savePrediction(prediction: WaferPrediction): WaferPrediction {
        val values = ContentValues().apply { put("wafer_id",prediction.waferId); put("model_hash",prediction.modelSha256); put("result_json",gson.toJson(prediction)) }
        writableDatabase.insertWithOnConflict("predictions",null,values,SQLiteDatabase.CONFLICT_IGNORE)
        return requireNotNull(prediction(prediction.waferId,prediction.modelSha256))
    }
    @Synchronized internal fun recordInspection(prediction: WaferPrediction, threshold: Int): InspectionRecord {
        require(threshold in 1..100)
        val db = writableDatabase
        require(db.rawQuery("SELECT 1 FROM predictions WHERE id=? AND wafer_id=?",
            arrayOf(prediction.id.toString(), prediction.waferId.toString())).use { it.moveToFirst() })
        val record = InspectionRecord(prediction.waferId, prediction.id, threshold, Instant.now().toString())
        val values = ContentValues().apply {
            put("id", record.waferId)
            put("prediction_id", record.predictionId)
            put("threshold", record.threshold)
            put("created_at", record.checkedAt)
        }
        db.replaceOrThrow("inspections", null, values)
        return record
    }

    @Synchronized internal fun saveInspection(prediction: WaferPrediction, threshold: Int): InspectionResult {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val saved = if (prediction.id == 0) savePrediction(prediction) else prediction
            val record = recordInspection(saved, threshold)
            db.setTransactionSuccessful()
            return InspectionResult(record, saved)
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized internal fun latestInspection(waferId: Int): InspectionResult? =
        readableDatabase.rawQuery(
            "SELECT i.*, p.result_json FROM inspections i JOIN predictions p ON p.id=i.prediction_id WHERE i.id=?",
            arrayOf(waferId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val record = cursor.inspection()
            InspectionResult(record, gson.fromJson(cursor.text("result_json"), WaferPrediction::class.java)
                .copy(id = record.predictionId))
        }

    @Synchronized internal fun recentInspections(): List<InspectionPoint> = readableDatabase.rawQuery(
        """
        SELECT i.*, l.lot_code, w.wafer_code, w.good_count, w.bad_count, p.result_json
        FROM inspections i
        JOIN wafers w ON w.id=i.id
        JOIN lots l ON l.id=w.lot_id
        JOIN predictions p ON p.id=i.prediction_id
        ORDER BY julianday(i.created_at) DESC, i.id DESC LIMIT 30
        """.trimIndent(), null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val prediction = gson.fromJson(cursor.text("result_json"), WaferPrediction::class.java)
                add(InspectionPoint(cursor.inspection(), cursor.text("lot_code"), cursor.text("wafer_code"),
                    cursor.int("good_count"), cursor.int("bad_count"), prediction.label, prediction.labelZh))
            }
        }
    }

    private fun Cursor.inspection() = InspectionRecord(int("id"), int("prediction_id"), int("threshold"), text("created_at"))
    @Synchronized fun saveAnalysis(waferId: Int, predictionId: Int, model: String, content: String): AiAnalysis {
        val date=Instant.now().toString()
        val values=ContentValues().apply { put("wafer_id",waferId); put("prediction_id",predictionId); put("model",model); put("content",content); put("created_at",date) }
        val id=writableDatabase.insertOrThrow("analyses",null,values).toInt()
        return AiAnalysis(id,waferId,predictionId,model,content,date)
    }
    @Synchronized fun latestAnalysis(waferId: Int, predictionId: Int): AiAnalysis? = readableDatabase.rawQuery("SELECT * FROM analyses WHERE wafer_id=? AND prediction_id=? ORDER BY id DESC LIMIT 1",arrayOf(waferId.toString(),predictionId.toString())).use {
        if(it.moveToFirst()) AiAnalysis(it.int("id"),waferId,predictionId,it.text("model"),it.text("content"),it.text("created_at")) else null
    }
    private fun Cursor.text(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
}
