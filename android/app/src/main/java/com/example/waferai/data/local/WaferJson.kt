package com.example.waferai.data.local

import com.google.gson.JsonParser
import com.example.waferai.data.remote.WaferDetail
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ParsedWafer(val detail: WaferDetail, val inputHash: String)

object WaferJson {
    const val MAX_BYTES = 2 * 1024 * 1024
    fun parse(bytes: ByteArray, filename: String): ParsedWafer {
        val text = decodeWaferText(bytes)
        val json = runCatching { JsonParser.parseString(text).asJsonObject }
            .getOrElse { throw IllegalArgumentException("JSON 格式不正确，请检查文件内容。") }
        require(json.keySet() == setOf("lot_code", "wafer_code", "map")) { "JSON 须且仅包含 lot_code、wafer_code 和 map。" }
        fun code(key: String): String {
            val item = json.get(key)
            require(item.isJsonPrimitive && item.asJsonPrimitive.isString) { "$key 必须是文字。" }
            return validateWaferCode(item.asString, key)
        }
        val lot = code("lot_code"); val wafer = code("wafer_code")
        require(json["map"].isJsonArray) { "map 必须是二维矩阵。" }
        val rows = json.getAsJsonArray("map")
        require(rows.size() in 1..512) { "矩阵行数须为 1～512。" }
        var columns = -1
        val map = rows.map { row ->
            require(row.isJsonArray && row.asJsonArray.size() in 1..512) { "矩阵列数须为 1～512。" }
            if (columns == -1) columns = row.asJsonArray.size()
            require(row.asJsonArray.size() == columns) { "矩阵每行的列数必须相同。" }
            row.asJsonArray.map { bin ->
                val token=bin.toString()
                require(bin.isJsonPrimitive && bin.asJsonPrimitive.isNumber && (token=="0" || token=="1" || token=="2")) { "矩阵元素须为整数 0、1、2，不接受小数或布尔值。" }
                bin.asInt
            }
        }
        return parsedWafer(lot, wafer, map, filename)
    }
    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

internal fun decodeWaferText(bytes: ByteArray): String {
    require(bytes.size <= WaferJson.MAX_BYTES) { "文件不能超过 2 MB。" }
    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
}

internal fun validateWaferCode(value: String, key: String): String = value.trim().also {
    require(it.isNotEmpty() && it.codePointCount(0, it.length) <= 64 && it.none(Char::isISOControl)) {
        "$key 长度须为 1～64 个字符，不能包含控制字符。"
    }
}

internal fun parsedWafer(lot: String, wafer: String, map: List<List<Int>>, filename: String): ParsedWafer {
    val good = map.sumOf { row -> row.count { it == 1 } }
    val bad = map.sumOf { row -> row.count { it == 2 } }
    require(good + bad > 0) { "矩阵中没有有效芯片。" }
    val detail = WaferDetail(
        0, lot, wafer, filename.take(180), map.size, map.first().size,
        good, bad, good + bad, good.toDouble() / (good + bad), map
    )
    val canonical = ByteBuffer.allocate(8 + detail.rows * detail.cols).putInt(detail.rows).putInt(detail.cols)
    map.forEach { row -> row.forEach { canonical.put(it.toByte()) } }
    return ParsedWafer(detail, WaferJson.sha256(canonical.array()))
}
