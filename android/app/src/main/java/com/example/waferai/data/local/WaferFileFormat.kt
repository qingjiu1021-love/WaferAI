package com.example.waferai.data.local

import com.example.waferai.data.remote.WaferDetail
import java.io.OutputStream
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

internal enum class WaferFileFormat(val extension: String, val mimeType: String) {
    STDF("stdf", "application/octet-stream"),
    ATDF("atdf", "application/octet-stream"),
    CSV("csv", "text/csv"),
    JSON("json", "application/json"),
    PARQUET("parquet", "application/vnd.apache.parquet");

    companion object {
        fun fromFilename(name: String): WaferFileFormat? = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "std" -> STDF
            "adtf" -> ATDF
            "cvs" -> CSV
            else -> entries.firstOrNull { it.extension == name.substringAfterLast('.', "").lowercase(Locale.ROOT) }
        }
    }
}

internal object WaferFileWriter {
    fun json(wafer: WaferDetail): String = com.google.gson.Gson().toJson(
        mapOf("lot_code" to wafer.lotCode, "wafer_code" to wafer.waferCode, "map" to wafer.map)
    )

    fun csv(wafer: WaferDetail, output: Writer, checkCancelled: () -> Unit = {}) {
        fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""
        val prefix = "${quote(wafer.lotCode)},${quote(wafer.waferCode)},"
        output.write("lot_code,wafer_code,row,col,status\r\n")
        wafer.map.forEachIndexed { row, bins ->
            checkCancelled()
            bins.forEachIndexed { col, status ->
                // Listing the last cell preserves empty rows and columns at the outer edge.
                if (status != 0 || (row == wafer.rows - 1 && col == wafer.cols - 1)) {
                    output.write("$prefix${row + 1},${col + 1},$status\r\n")
                }
            }
        }
    }

    fun stdf(wafer: WaferDetail, output: OutputStream, checkCancelled: () -> Unit = {}) {
        val stamp = Instant.now().epochSecond.toInt()
        val ids = TestGridNotes(wafer)
        val records = StdfRecords(output)
        records.record(0, 10) { put(2); put(4) }
        records.record(1, 10) {
            putInt(stamp); putInt(stamp); put(0)
            repeat(3) { put(32) }; putShort((-1).toShort()); put(32)
            cn(ids.lot); cn(""); cn(""); cn(""); cn("WaferAI")
        }
        records.record(1, 80) { put(1); put(1); put(1); put(1) }
        ids.notes.forEach { note -> records.record(50, 30) { cn(note) } }
        records.record(2, 10) { put(1); put(1); putInt(stamp); cn(ids.wafer) }
        wafer.map.forEachIndexed { row, bins ->
            checkCancelled()
            bins.forEachIndexed { col, status ->
                if (status != 0) {
                    records.record(5, 10) { put(1); put(1) }
                    records.record(5, 20) {
                        put(1); put(1); put(if (status == 2) 8 else 0)
                        putShort(0); putShort(status.toShort()); putShort((-1).toShort())
                        putShort((col + 1).toShort()); putShort((wafer.rows - row).toShort()); putInt(0)
                    }
                }
            }
        }
        records.record(2, 20) {
            put(1); put(1); putInt(stamp); putInt(wafer.totalCount)
            putInt(0); putInt(0); putInt(wafer.goodCount); putInt(-1); cn(ids.wafer)
        }
        records.record(1, 30) { put(255.toByte()); put(0); putInt(wafer.totalCount); putInt(0); putInt(0); putInt(wafer.goodCount) }
        records.record(1, 20) { putInt(stamp); put(32); cn("WaferAI derived wafer map") }
    }

    fun atdf(wafer: WaferDetail, output: Writer, checkCancelled: () -> Unit = {}) {
        val stamp = DateTimeFormatter.ofPattern("HH:mm:ss dd-MMM-uuuu", Locale.US)
            .withZone(ZoneOffset.UTC).format(Instant.now()).uppercase(Locale.ROOT)
        val ids = TestGridNotes(wafer)
        output.write("FAR:A|4|2|U\nMIR:${ids.lot}||WaferAI|||$stamp|$stamp|||0\nSDR:1|1|1\n")
        ids.notes.forEach { output.write("DTR:$it\n") }
        output.write("WIR:1|$stamp|1|${ids.wafer}\n")
        wafer.map.forEachIndexed { row, bins ->
            checkCancelled()
            bins.forEachIndexed { col, status ->
                if (status != 0) {
                    val result = if (status == 1) "P" else "F"
                    output.write("PIR:1|1\nPRR:1|1||0|$result|$status||${col + 1}|${wafer.rows - row}|||0\n")
                }
            }
        }
        output.write("WRR:1|$stamp|${wafer.totalCount}|${ids.wafer}|1|0|0|${wafer.goodCount}\n")
        output.write("PCR:255|0|${wafer.totalCount}|0|0|${wafer.goodCount}\nMRR:$stamp||WaferAI derived wafer map\n")
    }

    private class StdfRecords(private val output: OutputStream) {
        private val payload = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)

        fun record(type: Int, subtype: Int, body: ByteBuffer.() -> Unit) {
            payload.clear()
            payload.body()
            val length = payload.position()
            output.write(length and 255)
            output.write(length ushr 8)
            output.write(type)
            output.write(subtype)
            output.write(payload.array(), 0, length)
        }
    }

    private fun ByteBuffer.cn(value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        require(bytes.size <= 255)
        put(bytes.size.toByte())
        put(bytes)
    }
}

// Standard DTR text preserves the grid extent and original IDs when native fields need ASCII.
internal class TestGridNotes(detail: WaferDetail) {
    val lot = asciiId(detail.lotCode, "LOT")
    val wafer = asciiId(detail.waferCode, "WAFER")
    val notes = buildList {
        add("${Prefix}grid:${detail.rows}:${detail.cols}")
        fun addId(field: String, original: String, ascii: String) {
            if (original == ascii) return
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(original.toByteArray())
            encoded.chunked(200).forEachIndexed { index, chunk -> add("$Prefix$field:$index:$chunk") }
        }
        addId("lot", detail.lotCode, lot)
        addId("wafer", detail.waferCode, wafer)
    }

    companion object {
        const val Prefix = "WaferAI-map-v1:"

        private fun asciiId(value: String, kind: String): String =
            if (value.all { it in ' '..'~' && it != '|' }) value
            else "WA-$kind-${WaferJson.sha256(value.toByteArray()).take(12)}"

        fun original(notes: List<String>, field: String, fallback: String): String {
            val parts = notes.filter { it.startsWith("$Prefix$field:") }.map { it.substringAfter("$Prefix$field:") }
            if (parts.isEmpty()) return fallback
            require(parts.size <= 2 && parts.withIndex().all { (index, part) -> part.startsWith("$index:") }) {
                "晶圆标识扩展记录不完整。"
            }
            val bytes = Base64.getUrlDecoder().decode(parts.joinToString("") { it.substringAfter(':') })
            return validateWaferCode(decodeWaferText(bytes), field)
        }
    }
}
