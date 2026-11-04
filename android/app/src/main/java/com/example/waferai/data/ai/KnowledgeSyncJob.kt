package com.example.waferai.data.ai

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.example.waferai.WaferAiApplication
import com.example.waferai.ui.theme.AppearancePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class KnowledgeSyncJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = mutableMapOf<Int, Job>()
    private val container get() = (application as WaferAiApplication).container

    override fun onStartJob(params: JobParameters): Boolean {
        val prefs = AppearancePreferences(this).load()
        val automatic = params.jobId == Initial || params.jobId == Periodic
        val task = container.knowledgeTasks.tasks.value.firstOrNull { it.id == params.jobId }
        if (automatic) {
            if (!prefs.autoKnowledge || !hasSources(container.knowledge)) return false
        } else if (task?.enabled != true || task.profileId == null) return false
        jobs[params.jobId] = scope.launch {
            val retry = try {
                if (automatic) container.knowledge.refresh(minimumAgeMs = minimumAge(prefs.knowledgeHours))
                else withContext(Dispatchers.IO) {
                    container.knowledgeTasks.execute(params.jobId, container.importRepository.aiProfiles, scheduled = true)
                }
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                automatic
            }
            jobs.remove(params.jobId)
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        return (params.jobId == Initial || params.jobId == Periodic) && AppearancePreferences(this).load().autoKnowledge
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val Initial = 7091
        private const val Periodic = 7092
        private fun minimumAge(hours: Int) = TimeUnit.HOURS.toMillis(hours.toLong()) * 4 / 5
        private fun hasSources(knowledge: WaferKnowledge) = SearchPlugin.entries.any { knowledge.plugins.state(it).enabled }

        internal fun configure(context: Context, knowledge: WaferKnowledge) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val tasks = (context.applicationContext as WaferAiApplication).container.knowledgeTasks
            tasks.tasks.value.filter { it.profileId == null }.forEach { scheduler.cancel(it.id) }
            val prefs = AppearancePreferences(context).load()
            if (!prefs.autoKnowledge || !hasSources(knowledge)) {
                scheduler.cancel(Initial)
                scheduler.cancel(Periodic)
                return
            }
            schedulePeriodic(context, scheduler, Periodic, prefs.knowledgeHours)
            if (System.currentTimeMillis() - knowledge.state.value.syncedAt >= minimumAge(prefs.knowledgeHours) &&
                scheduler.getPendingJob(Initial) == null) {
                scheduler.schedule(builder(context, Initial).build())
            }
        }

        internal fun configureTask(context: Context, task: KnowledgeTask) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: error("系统任务服务不可用。")
            if (!task.enabled || task.profileId == null) scheduler.cancel(task.id)
            else check(schedulePeriodic(context, scheduler, task.id, task.hours)) { "系统未能安排定时任务。" }
        }

        internal fun cancelTask(context: Context, id: Int) {
            context.getSystemService(JobScheduler::class.java)?.cancel(id)
        }

        private fun schedulePeriodic(context: Context, scheduler: JobScheduler, id: Int, hours: Int): Boolean {
            val millis = TimeUnit.HOURS.toMillis(hours.toLong())
            return scheduler.getPendingJob(id)?.intervalMillis == millis ||
                scheduler.schedule(builder(context, id).setPeriodic(millis).build()) == JobScheduler.RESULT_SUCCESS
        }

        private fun builder(context: Context, id: Int) =
            JobInfo.Builder(id, ComponentName(context, KnowledgeSyncJob::class.java))
                .setPersisted(true)
                .setBackoffCriteria(TimeUnit.HOURS.toMillis(1), JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .apply {
                    if (Build.VERSION.SDK_INT >= 28) {
                        setRequiredNetwork(NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build())
                    } else setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                }
    }
}
