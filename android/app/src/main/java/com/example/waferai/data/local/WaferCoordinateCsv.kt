package com.example.waferai.data.local

private const val MatrixLimit = 512
private val RequiredColumns = setOf("lot_code", "wafer_code", "row", "col", "status")

internal object WaferCoordinateCsv {
    fun parse(bytes: ByteArray, filename: String): ParsedWafer {
        val lines = decodeWaferText(bytes).lineSequence().iterator()
        require(lines.hasNext()) { "CSV 缺少表头。" }
        val header = fields(lines.next(), 1).map { it.lowercase() }
        require(header.size == RequiredColumns.size && header.toSet() == RequiredColumns) {
            "CSV 表头必须包含 lot_code、wafer_code、row、col、status 五列。"
        }
        val lotColumn = header.indexOf("lot_code")
        val waferColumn = header.indexOf("wafer_code")
        val rowColumn = header.indexOf("row")
        val colColumn = header.indexOf("col")
        val statusColumn = header.indexOf("status")
        // 0 marks an unlisted coordinate. Listed statuses are stored as 1 through 3.
        val cells = ByteArray(MatrixLimit * MatrixLimit)
        var lot = ""
        var wafer = ""
        var rows = 0
        var cols = 0
        var lineNumber = 1
        while (lines.hasNext()) {
            val line = lines.next()
            lineNumber++
            if (line.isBlank()) continue
            val values = fields(line, lineNumber)
            require(values.size == header.size) { "CSV 第 ${lineNumber} 行须有五列。" }
            if (rows == 0) {
                lot = validateWaferCode(values[lotColumn], "lot_code")
                wafer = validateWaferCode(values[waferColumn], "wafer_code")
            }
            require(lot == values[lotColumn] && wafer == values[waferColumn]) {
                "CSV 第 ${lineNumber} 行的批次或晶圆编号不同。每个文件只能包含一张晶圆。"
            }
            val row = values[rowColumn].toIntOrNull()?.takeIf { it in 1..MatrixLimit }
            val col = values[colColumn].toIntOrNull()?.takeIf { it in 1..MatrixLimit }
            require(row != null && col != null) { "CSV 第 ${lineNumber} 行的行列须为 1～512 的整数。" }
            val status = values[statusColumn]
            require(status == "0" || status == "1" || status == "2") {
                "CSV 第 ${lineNumber} 行的状态须为 0、1 或 2。"
            }
            val index = (row - 1) * MatrixLimit + col - 1
            require(cells[index] == 0.toByte()) { "CSV 第 ${lineNumber} 行的坐标重复。" }
            cells[index] = (status.toInt() + 1).toByte()
            rows = maxOf(rows, row)
            cols = maxOf(cols, col)
        }
        require(rows > 0) { "CSV 没有芯片坐标记录。" }
        val map = List(rows) { row ->
            List(cols) { col -> (cells[row * MatrixLimit + col].toInt() - 1).coerceAtLeast(0) }
        }
        return parsedWafer(lot, wafer, map, filename)
    }

    private fun fields(line: String, lineNumber: Int): List<String> = buildList {
        val value = StringBuilder()
        var quoted = false
        var closedQuote = false
        var index = 0
        while (index < line.length) {
            val char = line[index++]
            when {
                quoted && char == '"' && line.getOrNull(index) == '"' -> {
                    value.append('"')
                    index++
                }
                char == '"' && quoted -> {
                    quoted = false
                    closedQuote = true
                }
                quoted -> value.append(char)
                char == ',' -> {
                    add(value.toString().trim())
                    require(size < RequiredColumns.size) { "CSV 第 ${lineNumber} 行须有五列。" }
                    value.clear()
                    closedQuote = false
                }
                char == '"' && !closedQuote && value.isBlank() -> {
                    value.clear()
                    quoted = true
                }
                !closedQuote && char != '"' -> value.append(char)
                else -> require(char.isWhitespace()) { "CSV 第 ${lineNumber} 行的引号格式不正确。" }
            }
        }
        require(!quoted) { "CSV 第 ${lineNumber} 行的引号格式不正确。" }
        add(value.toString().trim())
    }
}
