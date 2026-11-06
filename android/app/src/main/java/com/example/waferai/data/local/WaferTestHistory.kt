package com.example.waferai.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.waferai.data.remote.WaferPrediction
import com.google.gson.Gson
import java.io.File
import java.time.Instant

internal data class WaferTestHistoryEntry(
    val id: Long,
    val mode: String,
    val waferName: String,
    val completedAt: Instant,
    val yieldRate: Double
)

internal data class SavedWaferTest(
    val plan: WaferTestSnapshot,
    val prediction: WaferPrediction?
)

/** Test snapshots use a separate local database, outside the imported-wafer backup. */
internal class WaferTestHistory(context: Context, name: String = "waferai-test-history.db") :
    SQLiteOpenHelper(context, File(context.noBackupFilesDir, name).absolutePath, null, 1) {
    private val gson = Gson()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE runs(
            id INTEGER PRIMARY KEY,
            mode TEXT NOT NULL,
            wafer_name TEXT NOT NULL,
            completed_at TEXT NOT NULL,
            yield_rate REAL NOT NULL,
            result TEXT NOT NULL
        )""".trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported test history migration")

    fun save(plan: WaferTestPlan, prediction: WaferPrediction?, completedAt: Instant) {
        val values = ContentValues().apply {
            put("mode", plan.mode)
            put("wafer_name", "${plan.wafer.lotCode} / ${plan.wafer.waferCode}")
            put("completed_at", completedAt.toString())
            put("yield_rate", plan.wafer.yieldRate)
            put("result", gson.toJson(SavedWaferTest(plan.snapshot(), prediction)))
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("runs", null, values)
            db.execSQL("DELETE FROM runs WHERE id IN (SELECT id FROM runs ORDER BY id DESC LIMIT -1 OFFSET $Capacity)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun list(): List<WaferTestHistoryEntry> = readableDatabase.rawQuery(
        "SELECT id,mode,wafer_name,completed_at,yield_rate FROM runs ORDER BY id DESC", null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(WaferTestHistoryEntry(cursor.getLong(0), cursor.getString(1),
                cursor.getString(2), Instant.parse(cursor.getString(3)), cursor.getDouble(4)))
        }
    }

    fun open(id: Long): Pair<SavedWaferTest, Instant> = readableDatabase.rawQuery(
        "SELECT result,completed_at FROM runs WHERE id=?", arrayOf(id.toString())
    ).use { cursor ->
        check(cursor.moveToFirst()) { "这条测试记录已不存在。" }
        gson.fromJson(cursor.getString(0), SavedWaferTest::class.java) to Instant.parse(cursor.getString(1))
    }

    companion object {
        const val Capacity = 30
    }
}
