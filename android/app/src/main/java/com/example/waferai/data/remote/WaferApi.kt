package com.example.waferai.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import okhttp3.MultipartBody

data class HealthResponse(
    val status: String,
    val service: String,
    @SerializedName("api_version") val apiVersion: String
)

interface WaferApi {
    @GET("api/v1/health")
    suspend fun health(): HealthResponse

    @Multipart
    @POST("api/v1/imports")
    suspend fun importWafer(@Part file: MultipartBody.Part): ImportResponse

    @POST("api/v1/wafers/{waferId}/predictions")
    suspend fun predictWafer(@Path("waferId") waferId: Int): PredictionResponse

    @GET("api/v1/wafers/{waferId}/predictions/latest")
    suspend fun latestPrediction(@Path("waferId") waferId: Int): WaferPrediction

    @GET("api/v1/wafers")
    suspend fun listWafers(@Query("limit") limit: Int, @Query("offset") offset: Int,
                           @Query("lot_id") lotId: Int? = null): List<WaferRecord>

    @GET("api/v1/wafers/{waferId}")
    suspend fun waferDetail(@Path("waferId") waferId: Int): WaferDetail

    @GET("api/v1/lots")
    suspend fun listLots(@Query("limit") limit: Int, @Query("offset") offset: Int): List<LotSummary>

    @GET("api/v1/lots/{lotId}")
    suspend fun lotDetail(@Path("lotId") lotId: Int): LotSummary
}

data class LotSummary(
    val id: Int,
    @SerializedName("lot_code") val lotCode: String,
    @SerializedName("wafer_count") val waferCount: Int,
    @SerializedName("good_count") val goodCount: Long,
    @SerializedName("bad_count") val badCount: Long,
    @SerializedName("total_count") val totalCount: Long,
    @SerializedName("yield_rate") val yieldRate: Double?,
    @SerializedName("last_imported_at") val lastImportedAt: String?
)

data class WaferRecord(
    val id: Int,
    @SerializedName("lot_code") val lotCode: String,
    @SerializedName("wafer_code") val waferCode: String,
    val filename: String,
    @SerializedName("total_count") val totalCount: Int,
    @SerializedName("yield_rate") val yieldRate: Double,
    @SerializedName("created_at") val createdAt: String
)

data class WaferDetail(
    val id: Int,
    @SerializedName("lot_code") val lotCode: String,
    @SerializedName("wafer_code") val waferCode: String,
    val filename: String,
    val rows: Int,
    val cols: Int,
    @SerializedName("good_count") val goodCount: Int,
    @SerializedName("bad_count") val badCount: Int,
    @SerializedName("total_count") val totalCount: Int,
    @SerializedName("yield_rate") val yieldRate: Double,
    val map: List<List<Int>>
)

data class ImportResponse(
    @SerializedName("already_imported") val alreadyImported: Boolean,
    val wafer: WaferDetail
)

data class ClassScore(
    val label: String,
    @SerializedName("label_zh") val labelZh: String,
    val score: Double
)

data class WaferPrediction(
    val id: Int,
    @SerializedName("wafer_id") val waferId: Int,
    @SerializedName("model_version") val modelVersion: String,
    @SerializedName("model_sha256") val modelSha256: String,
    @SerializedName("preprocessing_id") val preprocessingId: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("label_zh") val labelZh: String,
    val label: String,
    val score: Double,
    @SerializedName("score_type") val scoreType: String,
    val scores: List<ClassScore>
)

data class PredictionResponse(
    @SerializedName("already_predicted") val alreadyPredicted: Boolean,
    val prediction: WaferPrediction
)
