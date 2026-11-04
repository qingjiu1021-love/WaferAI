package com.example.waferai.data.ai

private val heading = Regex("^ {0,3}#{1,6}[\\t ]+")
private val headingEnd = Regex("[\\t ]+#+[\\t ]*$")
private val bullet = Regex("^(\\s*)[-+*][\\t ]+")
private val rule = Regex("^ {0,3}(?:\\*(?:[ \\t]*\\*){2,}|-(?:[ \\t]*-){2,}|_(?:[ \\t]*_){2,})[ \\t]*$")
private val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
private val inlineCode = Regex("(`+)([^`]+)\\1")
private val strong = Regex("(?<!\\*)(\\*{2,3})(?=\\S)(.+?)(?<=\\S)\\1(?!\\*)")
private val emphasis = Regex("(?<![A-Za-z0-9*])\\*(?=\\S)(.+?)(?<=\\S)\\*(?![A-Za-z0-9*])")

internal fun readableReply(value: String): String {
    var code: String? = null
    val lines = value.lineSequence().mapNotNull { line ->
        val marker = fence.matchEntire(line)
        val active = code
        if (active != null) {
            if (marker != null && marker.groupValues[1].first() == active.first() &&
                marker.groupValues[1].length >= active.length && marker.groupValues[2].isBlank()) {
                code = null
                null
            } else line
        } else if (marker != null) {
            code = marker.groupValues[1]
            null
        } else if (rule.matches(line)) {
            ""
        } else {
            val title = if (heading.containsMatchIn(line)) line.replaceFirst(heading, "").replace(headingEnd, "") else line
            val plain = title.replaceFirst(bullet, "$1• ")
            cleanInline(plain)
        }
    }
    return lines.joinToString("\n").trim('\n', '\r')
}

private fun cleanInline(line: String): String {
    if (line.none { it == '\u0060' }) return stripEmphasis(line)
    val codeSpans = mutableListOf<String>()
    var marker = "\uE000"
    while (marker in line) marker += "\uE000"
    val protected = inlineCode.replace(line) { code ->
        codeSpans.add(code.groupValues[2])
        "$marker${codeSpans.lastIndex}\uE001"
    }
    return Regex("$marker(\\d+)\uE001").replace(stripEmphasis(protected)) {
        codeSpans[it.groupValues[1].toInt()]
    }
}

private fun stripEmphasis(line: String) = line.replace(strong, "$2").replace(emphasis, "$1")
