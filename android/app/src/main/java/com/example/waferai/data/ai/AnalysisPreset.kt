package com.example.waferai.data.ai

enum class AnalysisPreset(val title: String, val maxHypotheses: Int, val maxSteps: Int, val instruction: String) {
    QUICK("快速解读", 1, 2, "重点是一句话结论与最值得查看的空间现象，适合快速阅读。"),
    ENGINEERING("工程排查", 3, 6, "按先核对数据、再查看空间证据、再验证机理的顺序给出可执行排查步骤。"),
    THESIS("论文说明", 2, 4, "使用客观、可复核的研究表述，区分测量、算法输出与待验证解释，不夸大创新和性能。"),
    CHARTS("图表解释", 1, 3, "重点解释良率占比、分区差异和行列空间分布，明确空间位置曲线不是时间趋势。")
}
