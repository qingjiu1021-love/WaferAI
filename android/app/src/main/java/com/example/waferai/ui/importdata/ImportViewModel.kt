package com.example.waferai.ui.importdata

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.waferai.data.remote.WaferDetail
import com.example.waferai.data.remote.WaferPrediction
import com.example.waferai.data.local.DemoWafer
import com.example.waferai.data.local.WaferImageOptions
import com.example.waferai.data.report.ReportSnapshot
import com.example.waferai.data.repository.ImportRepository
import com.example.waferai.data.local.AiAnalysis
import com.example.waferai.data.local.InspectionRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException

data class ImportUiState(
    val loading: Boolean = false,
    val wafer: WaferDetail? = null,
    val notice: String? = null,
    val error: String? = null,
    val predicting: Boolean = false,
    val prediction: WaferPrediction? = null,
    val inspection: InspectionRecord? = null,
    val predictionNotice: String? = null,
    val predictionError: String? = null,
    val exporting: Boolean = false,
    val exportMessage: String? = null,
    val exportError: String? = null,
    val analyzing: Boolean = false,
    val analysis: AiAnalysis? = null,
    val analysisError: String? = null
)

class ImportViewModel(private val repository: ImportRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ImportUiState())
    val state = mutableState.asStateFlow()
    private var pendingReport: ReportSnapshot? = null
    private var analysisJob: Job? = null

    fun loadDemo(sample: DemoWafer) {
        val current=mutableState.value
        if(current.loading || current.predicting || current.exporting || current.analyzing) return
        mutableState.value=ImportUiState(loading=true)
        viewModelScope.launch {
            try {
                val result=repository.loadDemo(sample)
                mutableState.value=ImportUiState(wafer=result.wafer,notice=if (sample.isReal) "已载入真实样本：${sample.title}，原始标注 ${sample.datasetLabel}。该标注不是本次 AI 预测，数据已保存在本机。" else "已载入手工构造的演示晶圆，数据保存在本机。")
                restorePrediction(result.wafer.id)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutableState.value=ImportUiState(error=error.message ?: "无法载入演示晶圆。") }
        }
    }

    fun analyzeWafer() {
        val current=mutableState.value
        val wafer=current.wafer ?: return
        val prediction=current.prediction ?: return
        if(current.loading || current.predicting || current.exporting || current.analyzing) return
        mutableState.value=current.copy(analyzing=true,analysisError=null)
        analysisJob=viewModelScope.launch {
            try {
                val analysis=repository.analyze(wafer,prediction)
                if(mutableState.value.wafer?.id==wafer.id) mutableState.value=mutableState.value.copy(analyzing=false,analysis=analysis)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutableState.value=mutableState.value.copy(analyzing=false,analysisError=error.message ?: "在线解析失败，请稍后重试。") }
        }
    }
    fun cancelAnalysis() {
        analysisJob?.cancel()
        mutableState.value=mutableState.value.copy(analyzing=false,analysisError="已取消在线解析。已保存的解析仍可查看。")
    }

    fun clearForServerChange() {
        viewModelScope.coroutineContext.cancelChildren()
        pendingReport = null
        mutableState.value = ImportUiState()
    }

    fun openWafer(waferId: Int): Boolean {
        val current = mutableState.value
        if (current.loading || current.predicting || current.exporting || current.analyzing) return false
        pendingReport = null
        mutableState.value = ImportUiState(loading = true)
        viewModelScope.launch {
            try {
                val wafer = repository.waferDetail(waferId)
                mutableState.value = ImportUiState(wafer = wafer, notice = "已打开历史记录。")
                restorePrediction(wafer.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: HttpException) {
                val message = runCatching {
                    JSONObject(error.response()?.errorBody()?.string().orEmpty())
                        .getJSONObject("error").getString("message")
                }.getOrDefault("无法打开历史记录，请检查分析服务后重试。")
                mutableState.value = ImportUiState(error = message)
            } catch (_: Exception) {
                mutableState.value = ImportUiState(error = "无法读取历史记录，请检查分析服务后重试。")
            }
        }
        return true
    }

    fun importFile(uri: Uri) = importFrom { repository.importFile(uri) }

    fun importImage(uri: Uri, options: WaferImageOptions, threshold: Int) =
        importFrom(inspectAt = threshold) { repository.importImage(uri, options) }

    private fun importFrom(inspectAt: Int? = null, read: suspend () -> com.example.waferai.data.remote.ImportResponse) {
        if (mutableState.value.loading || mutableState.value.predicting || mutableState.value.exporting || mutableState.value.analyzing) return
        mutableState.value = ImportUiState(loading = true)
        viewModelScope.launch {
            try {
                val result = read()
                mutableState.value = ImportUiState(
                    wafer = result.wafer,
                    notice = if (result.alreadyImported) "文件此前已导入，已打开已有记录。" else "导入完成，结果已保存。"
                )
                restorePrediction(result.wafer.id)
                if (inspectAt != null && mutableState.value.wafer?.id == result.wafer.id &&
                    mutableState.value.inspection == null) {
                    inspectWafer(inspectAt)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: HttpException) {
                val message = runCatching {
                    JSONObject(error.response()?.errorBody()?.string().orEmpty())
                        .getJSONObject("error").getString("message")
                }.getOrDefault("导入失败，请检查文件内容后重试。")
                mutableState.value = ImportUiState(error = message)
            } catch (error: IllegalArgumentException) {
                mutableState.value = ImportUiState(error = error.message ?: "文件格式不正确。")
            } catch (error: IOException) {
                mutableState.value = ImportUiState(error = "无法读取文件或保存到本机，请检查文件权限和存储空间。")
            } catch (error: Exception) {
                mutableState.value = ImportUiState(error = "无法导入该文件，请重新选择后重试。")
            }
        }
    }

    private suspend fun restorePrediction(waferId: Int) {
        try {
            val inspection = repository.latestInspection(waferId)
            val saved = inspection?.prediction ?: repository.latestPrediction(waferId)
            val analysis=repository.latestAnalysis(waferId,saved.id)
            val current = mutableState.value
            if (current.wafer?.id == waferId && !current.predicting && current.prediction == null) {
                mutableState.value = current.copy(prediction = saved, inspection = inspection?.record,
                    predictionNotice = "已读取本机保存的识别结果。", analysis = analysis)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No result yet or service temporarily unavailable: manual recognition remains available.
        }
    }

    fun inspectWafer(threshold: Int, trained: Boolean = false) {
        val current = mutableState.value
        val waferId = current.wafer?.id ?: return
        if (current.loading || current.predicting || current.exporting || current.analyzing) return
        mutableState.value = current.copy(predicting = true, predictionError = null, analysisError = null)
        viewModelScope.launch {
            try {
                val result = repository.inspectWafer(waferId, threshold, trained)
                val analysis=repository.latestAnalysis(waferId,result.prediction.id)
                if (mutableState.value.wafer?.id == waferId) {
                    mutableState.value = mutableState.value.copy(
                        predicting = false,
                        prediction = result.prediction,
                        inspection = result.record,
                        predictionNotice = "检测完成，结果已保存。",
                        analysis=analysis
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = when (error) {
                    is HttpException -> runCatching {
                        JSONObject(error.response()?.errorBody()?.string().orEmpty())
                            .getJSONObject("error").getString("message")
                    }.getOrDefault("识别失败，请检查分析服务后重试。")
                    is IOException -> "无法读取本地模型，请重新安装完整安装包。"
                    else -> error.message ?: "本地识别失败，请重试。"
                }
                if (mutableState.value.wafer?.id == waferId) {
                    mutableState.value = mutableState.value.copy(predicting = false, predictionError = message)
                }
            }
        }
    }

    fun prepareReportExport(): String? {
        val current = mutableState.value
        val wafer = current.wafer ?: return null
        if (current.loading || current.predicting || current.exporting || current.analyzing) return null
        val snapshot = ReportSnapshot(wafer, current.prediction, analysis = current.analysis, inspection = current.inspection)
        pendingReport = snapshot
        mutableState.value = current.copy(exporting = true, exportMessage = null, exportError = null)
        return snapshot.suggestedFilename()
    }

    fun cancelReportExport(message: String? = null) {
        pendingReport = null
        mutableState.value = mutableState.value.copy(exporting = false, exportError = message)
    }

    fun exportReport(uri: Uri) {
        val snapshot = pendingReport
        pendingReport = null
        if (snapshot == null) {
            cancelReportExport("页面数据已更新，请重新导入后导出。")
            return
        }
        viewModelScope.launch {
            try {
                repository.exportPdf(uri, snapshot)
                mutableState.value = mutableState.value.copy(exporting = false, exportMessage = "PDF 报告已保存到所选位置。")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(exporting = false, exportError = "导出失败，请重新选择保存位置后重试。")
            }
        }
    }

    class Factory(private val repository: ImportRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ImportViewModel::class.java))
            return ImportViewModel(repository) as T
        }
    }
}
