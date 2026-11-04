package com.example.waferai.data.local

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object WaferTestData {
    const val MAX_BYTES = 8 * 1024 * 1024

    fun stdf(bytes: ByteArray, filename: String): ParsedWafer {
        require(bytes.size >= 6 && bytes[2] == 0.toByte() && bytes[3] == 10.toByte() &&
            bytes[5] == 4.toByte()) { "需要带 FAR 文件头的 STDF V4 文件。" }
        val order = when (bytes[4].toInt()) {
            1 -> ByteOrder.BIG_ENDIAN
            0, 2 -> ByteOrder.LITTLE_ENDIAN
            else -> throw IllegalArgumentException("STDF CPU 类型暂不支持。")
        }
        val buffer = ByteBuffer.wrap(bytes).order(order)
        require(buffer.getShort(0).toInt() == 2) { "STDF FAR 记录长度异常。" }
        val wafer = DieMap(bytes, filename)
        while (buffer.hasRemaining()) {
            require(buffer.remaining() >= 4) { "STDF 记录头被截断。" }
            val length = buffer.short.toInt() and 0xffff
            val type = buffer.get().toInt() and 0xff
            val subtype = buffer.get().toInt() and 0xff
            require(buffer.remaining() >= length) { "STDF 记录内容被截断。" }
            val start = buffer.position()
            when {
                type == 1 && subtype == 10 -> wafer.lot = readCn(bytes, start, length, 15)
                type == 2 && subtype == 10 -> {
                    require(length >= 6) { "STDF WIR 记录不完整。" }
                    wafer.begin(readCn(bytes, start, length, 6))
                }
                type == 2 && subtype == 20 -> wafer.end(readCn(bytes, start, length, 26))
                type == 5 && subtype == 20 -> {
                    require(length >= 3) { "STDF PRR 记录不完整。" }
                    val flags = bytes[start + 2].toInt() and 0xff
                    if (length >= 13 && flags and 0x14 == 0) {
                        wafer.die(
                            buffer.getShort(start + 9).toInt(), buffer.getShort(start + 11).toInt(),
                            if (flags and 8 == 0) 1 else 2, flags and 2 != 0
                        )
                    }
                }
            }
            buffer.position(start + length)
        }
        return wafer.result()
    }

    fun atdf(bytes: ByteArray, filename: String): ParsedWafer {
        val wafer = DieMap(bytes, filename)
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val lines = text.lineSequence().filter { it.isNotBlank() }.iterator()
        require(lines.hasNext() && lines.next().startsWith("FAR:A|4|")) { "需要带 FAR:A|4 文件头的 ATDF 文件。" }
        while (lines.hasNext()) {
            val line = lines.next()
            val record = line.substringBefore(':')
            if (record != "MIR" && record != "WIR" && record != "WRR" && record != "PRR") continue
            val fields = line.substringAfter(':').split('|', limit = 13)
            when (record) {
                "MIR" -> wafer.lot = fields.first().trim()
                "WIR" -> wafer.begin(fields.getOrNull(3).orEmpty().trim())
                "WRR" -> wafer.end(fields.getOrNull(3).orEmpty().trim())
                "PRR" -> {
                    require(fields.size >= 9) { "ATDF PRR 记录缺少坐标字段。" }
                    val result = fields[4].trim().uppercase()
                    if (result !in setOf("P", "F") || fields.getOrNull(10)?.trim()?.uppercase() == "Y") continue
                    val x = fields[7].trim().toShortOrNull()?.toInt() ?: continue
                    val y = fields[8].trim().toShortOrNull()?.toInt() ?: continue
                    wafer.die(x, y, if (result == "P") 1 else 2, fields.getOrNull(9)?.trim()?.uppercase() == "C")
                }
            }
        }
        return wafer.result()
    }

    private fun readCn(bytes: ByteArray, start: Int, size: Int, offset: Int): String {
        if (offset >= size) return ""
        val length = bytes[start + offset].toInt() and 0xff
        require(offset + 1 + length <= size) { "STDF 字符串长度异常。" }
        return String(bytes, start + offset + 1, length, Charsets.ISO_8859_1).trim()
    }

    private class DieMap(private val source: ByteArray, private val filename: String) {
        var lot = ""
        private var code = ""
        private var started = false
        private var ended = false
        private val dies = HashMap<Pair<Int, Int>, Int>()

        fun begin(id: String) {
            require(!started && !ended && dies.isEmpty()) { "测试数据文件只能包含一张晶圆。" }
            started = true
            code = id
        }

        fun end(id: String) {
            require(!ended && (code.isBlank() || id.isBlank() || code == id)) { "测试数据文件只能包含一张晶圆。" }
            ended = true
            if (code.isBlank()) code = id
        }

        fun die(x: Int, y: Int, status: Int, retest: Boolean) {
            if (x == -32768 || y == -32768) return
            require(!ended) { "测试数据文件只能包含一张晶圆。" }
            val coord = x to y
            val previous = dies[coord]
            require(previous == null || retest || previous == status) { "同一坐标有冲突的电测结果，且未标记坐标复测。" }
            dies[coord] = status
            require(dies.size <= 512 * 512) { "测试数据超出 512 × 512 网格限制。" }
        }

        fun result(): ParsedWafer {
            require(dies.isNotEmpty()) { "未找到带有效坐标和合格状态的芯片记录。" }
            val minX = dies.keys.minOf { it.first }
            val maxX = dies.keys.maxOf { it.first }
            val minY = dies.keys.minOf { it.second }
            val maxY = dies.keys.maxOf { it.second }
            require(maxX - minX < 512 && maxY - minY < 512) { "测试数据超出 512 × 512 网格限制。" }
            val map = List(maxY - minY + 1) { row ->
                List(maxX - minX + 1) { col -> dies[col + minX to maxY - row] ?: 0 }
            }
            val lotId = validateWaferCode(lot.ifBlank { "ATE" }, "lot_code")
            val waferId = validateWaferCode(code.ifBlank { "ATE-${WaferJson.sha256(source).take(16)}" }, "wafer_code")
            return parsedWafer(lotId, waferId, map, filename)
        }
    }
}
