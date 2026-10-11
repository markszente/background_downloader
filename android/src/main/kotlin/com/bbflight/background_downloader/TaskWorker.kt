package com.bbflight.background_downloader

import android.app.Notification
import android.content.Context
import android.os.Build
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import kotlinx.serialization.json.Json
import android.util.Log

/**
 * The worker to execute one task
 *
 * It is now a wrapper around [TaskRunner], delegating the actual work to it.
 * This class implements [TaskJobContext] to provide the context for the [TaskRunner].
 */
@Suppress("ConstPropertyName")
open class TaskWorker(
    val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), TaskJobContext {

    companion object {
        const val keyTask = "Task"
        const val keyNotificationConfig = "notificationConfig"
        const val keyResumeDataData = "tempFilename"
        const val keyStartByte = "startByte"
        const val keyETag = "eTag"
        const val bufferSize = 2 shl 12

        /** Converts [Task] to JSON string representation */
        fun taskToJsonString(task: Task): String {
            return TaskRunner.taskToJsonString(task)
        }

        /**
         * Processes a change in status for the task
         *
         * Delegates to [TaskRunner.processStatusUpdate]
         */
        suspend fun processStatusUpdate(
            task: Task,
            status: TaskStatus,
            prefs: SharedPreferences,
            taskException: TaskException? = null,
            responseBody: String? = null,
            responseHeaders: Map<String, String>? = null,
            responseStatusCode: Int? = null,
            mimeType: String? = null,
            charSet: String? = null,
            context: Context
        ) {
            TaskRunner.processStatusUpdate(
                task,
                status,
                prefs,
                taskException,
                responseBody,
                responseHeaders,
                responseStatusCode,
                mimeType,
                charSet,
                context
            )
        }
    }

    override val appContext: Context
        get() = applicationContext

    // TaskJobContext Mutable properties
    override lateinit var task: Task
    override var notificationConfig: NotificationConfig? = null
    override var notificationId: Int = 0
    override var notificationProgress: Double = 2.0 // indeterminate
    override var networkSpeed: Double = -1.0
    override var taskCanResume: Boolean = false
    override var notificationConfigJsonString: String? = null
    override var runInForeground: Boolean = false

    override val isTaskStopped: Boolean
        get() = isStopped

    override val workStopReason: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stopReason
        else WorkInfo.STOP_REASON_UNKNOWN



    override val isActive: Boolean
        get() = !isTaskStopped

    override fun getInputLong(key: String, defaultValue: Long): Long {
        return inputData.getLong(key, defaultValue)
    }

    override fun getInputString(key: String): String? {
        return inputData.getString(key)
    }

    override suspend fun setForegroundNotification(
        notificationId: Int,
        notification: Notification,
        notificationType: Int
    ) {
        try {
            setForeground(ForegroundInfo(notificationId, notification, notificationType))
        } catch (e: IllegalStateException) {
            Log.d("TaskWorker", "Ignoring setForeground: worker already completed. ${e.message}")
        }
    }

    override suspend fun updateNotification(
        task: Task,
        status: TaskStatus,
        progress: Double,
        timeRemaining: Long
    ) {
        NotificationService.updateNotification(this, status, progress, timeRemaining)
    }

    override fun updateEstimatedNetworkBytes(downloadBytes: Long, uploadBytes: Long) {
        // No-op for TaskWorker
    }


    override suspend fun doWork(): Result {
        try {
            // Initialize task and notificationConfig from inputData
            val taskJson = inputData.getString(keyTask)
            if (taskJson != null) {
                task = Json.decodeFromString(taskJson)
            } else {
                return Result.failure()
            }

            // A job WorkManager runs for a task this plugin has already
            // finished does nothing. WorkManager's ForceStopRunnable
            // reschedules every job it still had running when the app was
            // force stopped (Settings, or MIUI's swipe from recents), and a
            // job is still running for a while after its task's final status:
            // the notification update, the holding queue enqueuing the next
            // task. Every job's task enters the task map before the job exists
            // (doEnqueue) and leaves it with the final status, so a job whose
            // task is not in the map is such a leftover: rerun, it downloaded
            // the file again, unasked (tourist_routes release test harness,
            // 2026-10-11: an 11.7 MB chunk after a force stop).
            val prefsForCheck = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            if (!getTaskMap(prefsForCheck).containsKey(task.taskId)) {
                Log.i(BDPlugin.TAG, "Skipping a job for taskId ${task.taskId}: the task is finished")
                return Result.success()
            }

            notificationConfigJsonString = inputData.getString(keyNotificationConfig)
            if (notificationConfigJsonString != null) {
                notificationConfig = Json.decodeFromString(notificationConfigJsonString!!)
            }
            
            // Check runInForeground pre-requisite (shared pref check done in Runner mostly, 
            // but might need initial value for context?)
             val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
             val runInForegroundFileSize = prefs.getInt(BDPlugin.keyConfigForegroundFileSize, -1)
             // simplified check, Runner does full check
             // But we need to set runInForeground to false initially
             runInForeground = false 

            val runner = createRunner()
            runner.run()
            
            return Result.success()
        } catch (e: Exception) {
            return Result.failure()
        }
    }

    /**
     * Create the appropriate [TaskRunner] for this worker
     *
     * Must be overridden by subclasses
     */
    open fun createRunner(): TaskRunner {
        // Should not be called directly on TaskWorker, but abstract not allowed on non-abstract class
        // And TaskWorker needs to be instantiable for UpdateNotificationWorker?
        // UpdateNotificationWorker overrides doWork entirely, so createRunner is not called.
        // But for other workers, it must be overridden.
        throw NotImplementedError("Subclasses must override createRunner")
    }
}
