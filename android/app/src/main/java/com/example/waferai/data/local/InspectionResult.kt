package com.example.waferai.data.local

import com.example.waferai.data.remote.WaferPrediction

data class InspectionRecord(val waferId: Int, val predictionId: Int, val threshold: Int, val checkedAt: String)

data class InspectionResult(val record: InspectionRecord, val prediction: WaferPrediction)

data class InspectionPoint(
    val record: InspectionRecord, val lot: String, val wafer: String,
    val good: Int, val bad: Int, val label: String, val labelZh: String
) {
    val yieldRate: Double get() = good.toDouble() / (good + bad)
    val belowThreshold: Boolean get() = 100L * good < record.threshold.toLong() * (good + bad)
}
