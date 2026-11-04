package com.example.waferai.data.local

enum class DemoWafer(val filename: String, val title: String, val description: String) {
    GENERAL("demo-wafer-001.json", "基础晶圆", "适合练习导入、点选和良率计算。"),
    EDGE_RING("demo-wafer-edge-ring.json", "边缘环状", "缺陷沿外圈分布，可查看边缘分区。"),
    SCRATCH("demo-wafer-scratch.json", "划痕状", "缺陷沿斜线分布，可放大查看单颗芯片。")
}
