package com.keshav.daptap.diag

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.keshav.daptap.DapTapApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Testing mode, every few hours: when due (about every 9 hours), sends the last 96 hours of the tap log again before ntfy's 12-hour cache drops
 * the previous copy, so a test can be read days later without anyone opening DapTap. Android runs it when there's a
 * network; each run is one short wake-up, and most find nothing due.
 */
class LogRefreshJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as DapTapApp
        work = app.scope.launch {
            val ok = runCatching { app.logUploader.refreshHistory() && app.logUploader.uploadNew() }.getOrDefault(false)
            jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    companion object {
        private const val ID = 4_1001
        /** Often enough that some run lands between [LogUploader.HISTORY_EVERY_MS] and ntfy's 12 hours. */
        private const val PERIOD_MS = 3 * 3_600_000L
        private const val FLEX_MS = 3_600_000L

        /** Schedules the job while testing mode is on, and cancels it when it's off. */
        fun set(context: Context, on: Boolean) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (!on) { js.cancel(ID); return }
            if (js.getPendingJob(ID) != null) return
            runCatching {
                js.schedule(JobInfo.Builder(ID, ComponentName(context, LogRefreshJob::class.java))
                    .setPeriodic(PERIOD_MS, FLEX_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build())
            }
        }
    }
}
