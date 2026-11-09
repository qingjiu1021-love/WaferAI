package com.example.waferai.data.local

enum class WaferSource(val label: String) {
    UNKNOWN("旧记录 · 来源未标记"),
    GRID_FILE("晶圆图数据文件"),
    TEST_FILE("STDF / ATDF 测试文件"),
    MAP_EXPORT("STDF / ATDF 晶圆图导出文件"),
    WM811K("WM-811K 公开样本"),
    DEMO("人工演示样本"),
    IMAGE("图片推算"),
    SIMULATION("教学仿真");

    val supportsStack get() = this == GRID_FILE || this == TEST_FILE || this == MAP_EXPORT || this == WM811K

    val description: String get() = when (this) {
        UNKNOWN -> "重新导入同一份数据可补记来源。"
        GRID_FILE -> "按文件内的晶圆网格和合格状态计算。"
        TEST_FILE -> "读取逐颗芯片的坐标和合格状态。"
        MAP_EXPORT -> "由晶圆网格转换，仅含坐标、合格状态和汇总计数。"
        WM811K -> "公开晶圆图，沿用数据集原有合格状态。"
        DEMO -> "用于练习操作的人工构造样本。"
        IMAGE -> "按图片网格和颜色推算，请核对原图。"
        SIMULATION -> "读数由软件生成。"
    }

    companion object {
        internal fun fromCode(code: String?) = entries.firstOrNull { it.name == code } ?: UNKNOWN
    }
}
