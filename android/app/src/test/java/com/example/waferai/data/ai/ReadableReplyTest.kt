package com.example.waferai.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadableReplyTest {
    @Test fun stripsDecorationAndKeepsSymbolsThatCarryMeaning() {
        val raw = "## **检测结果**\n* 良率**明显下降**，建议复核。\n***\n" +
            "公式 a*b，语言 C#，通配符 *.csv。\n**代码：`#include <stdio.h>`**\n" +
            "```c\n#define COUNT 8\nint *value;\n```"
        assertEquals("检测结果\n• 良率明显下降，建议复核。\n\n" +
            "公式 a*b，语言 C#，通配符 *.csv。\n代码：#include <stdio.h>\n" +
            "#define COUNT 8\nint *value;", readableReply(raw))
    }
}
