package com.example.waferai.data.ai

import com.example.waferai.data.local.DemoWafer

// Reviewed summaries and measured sample facts; never overwrite downloaded knowledge.
internal val WafertoolsKnowledge = listOf(
    KnowledgeEntry(DemoWafer.SourcePage, "wafertools 真实晶圆示例库", "首页「载入演示晶圆」内置 wafertools 真实数据页的 27 张 WM-811K 样本，每个原始标注类别 3 张，共 9 类；另外保留 3 张人工构造演示，总计 30 个可选样本。支持按类别、批次和晶圆编号查找，均可离线载入。", listOf("wafertools", "演示晶圆", "真实样本", "wm-811k", "样本库", "27", "30")),
    KnowledgeEntry(DemoWafer.SourcePage, "真实样本的标注与 AI 预测", "样本选择器展示的是 WM-811K 数据集原始人工标注。载入不会把标注当作 CNN 预测，也不会新增模型类别；请进入「AI 检测」运行独立识别。标注可能有主观性或错误，示例预测不能代替完整测试集评估。", listOf("wafertools", "原始标注", "人工标注", "ai预测", "训练", "测试", "验证", "九类")),
    KnowledgeEntry(DemoWafer.SourcePage, "真实样本的坐标与良率", "wafertools 示例用中心化坐标 x=列号−列数整除2，y=行号−行数整除2。WaferAI 反向还原原始行列，不旋转、不翻转、不缩放；缺失位置为0，hbin1为合格，hbin2为不合格。良率=合格数÷(合格数+不合格数)，非芯片区域不参与分母。", listOf("wafertools", "坐标", "还原", "良率", "hbin", "矩阵", "映射")),
    KnowledgeEntry(DemoWafer.SourcePage, "真实样本的来源与使用范围", "样本来自 wafertools/wafermap 固定提交 79f0c202f162c1c62fc3d027f6a88aef8b62ffe0 的 wm811k-sample.json，App 保留来源、校验值和 MIT 声明。原始 WM-811K 的 Kaggle 镜像标示 CC0；镜像元数据不代替原始发布者权利核验。这27张是展示子集，不代表总体分布，不用于新增训练、调参或重报准确率。", listOf("wafertools", "许可", "来源", "数据集", "cc0", "mit", "训练", "性能")),
    KnowledgeEntry(DemoWafer.SourcePage, "WM-811K · 中心型 Center", "不合格芯片主要在靠近中心的位置聚集。可对照中心、中部和边缘区域的不合格率；中心型标注不直接说明工艺根因。", listOf("wafertools", "wm-811k", "中心", "center")),
    KnowledgeEntry(DemoWafer.SourcePage, "WM-811K · 环带型 Donut", "不合格区域在中心与边缘之间形成环带；与贴近最外圈的 Edge-Ring 分开观察。真实样本可能含背景噪声，不必呈现完美圆环。", listOf("wafertools", "wm-811k", "环带", "甜甜圈", "donut")),
    KnowledgeEntry(DemoWafer.SourcePage, "WM-811K · 边缘局部型 Edge-Loc", "不合格芯片偏向边缘的一段或局部区域。与沿周边大范围分布的边缘环形区分，结合扇区分布复核。", listOf("wafertools", "wm-811k", "边缘局部", "edge-loc")),
    KnowledgeEntry(DemoWafer.SourcePage, "WM-811K · 边缘环形 Edge-Ring", "不合格芯片沿晶圆周边形成较大范围的环形分布。可放大外围并对照分区统计；不能仅凭图形断定具体设备或工艺异常。", listOf("wafertools", "wm-811k", "边缘环形", "边缘环状", "edge-ring")),
    KnowledgeEntry(DemoWafer.SourcePage, "WM-811K · 局部聚集型 Loc", "不合格芯片在某一局部区域聚集，位置未限定在中心或边缘。可通过连通区位置、大小和邻近芯片复核；不要把多个噪声点等同于连续聚集。", listOf("wafertools", "wm-811k", "局部聚集", "局部簇", "loc")),
    KnowledgeEntry(DemoWafer.SourcePage, "WM-811K · 近全失效型 Near-full", "大部分有效芯片处于不合格状态。先核对数据编码和测试结果，再分析异常分布；仍须用实际合格数除以有效芯片数计算良率。", listOf("wafertools", "wm-811k", "近全失效", "接近全失效", "near-full")),
    KnowledgeEntry(DemoWafer.SourcePage, "WM-811K · 随机型 Random", "不合格芯片较分散，缺少清晰的集中形状。此处是数据集原始模式标注，并不是统计学随机性检验通过的结论。", listOf("wafertools", "wm-811k", "随机", "random")),
    KnowledgeEntry(DemoWafer.SourcePage, "WM-811K · 划痕型 Scratch", "不合格芯片呈细长或条带分布，可放大查看原始位置。划痕型是空间模式名称，不足以证明已经发生实际机械划伤。", listOf("wafertools", "wm-811k", "划痕", "scratch")),
    KnowledgeEntry(DemoWafer.SourcePage, "WM-811K · 无特定缺陷模式 none", "none 是有标签的类别，表示未标注上述特定模式，不等于未标注的空标签，更不等于所有芯片合格。部分 none 样本仍有不合格芯片，应照原始矩阵计算良率。", listOf("wafertools", "wm-811k", "none", "无特定缺陷", "无缺陷", "空标签"))
) + DemoWafer.entries.filter { it.isReal }.map { sample ->
    val total = sample.goodCount + sample.badCount
    val yield = String.format(java.util.Locale.ROOT, "%.2f%%", sample.goodCount * 100.0 / total)
    KnowledgeEntry(DemoWafer.SourcePage, "真实样本 · ${sample.title}",
        "${sample.description}。原始标注 ${sample.datasetLabel}；合格 ${sample.goodCount}，不合格 ${sample.badCount}，有效芯片 $total，良率 $yield。来自 wafertools 真实数据页，保留原始矩阵。该标注不是本次 AI 预测；在首页「载入演示晶圆」搜索样本名可离线查看。",
        listOf(sample.title.lowercase(), sample.category.title, sample.datasetLabel!!.lowercase(), sample.lotCode.lowercase(), sample.waferCode.lowercase(), "wafertools", "真实样本"))
}
