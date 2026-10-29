package com.dewijones92.totum.groups

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.SourceGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

class FileGroupNewsStore(private val file: File) : GroupNewsStore {
    override suspend fun load(): Map<String, GroupNewsState> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyMap() else json.decodeFromString(serializer, file.readText())
    }

    override suspend fun save(states: Map<String, GroupNewsState>) = withContext(Dispatchers.IO) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(serializer, states))
        if (!temp.renameTo(file)) Diag.warn(TAG, "group news could not be moved into place; kept the old one")
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val serializer = MapSerializer(String.serializer(), GroupNewsState.serializer())
    }
}

class GroupNewsNotifier(private val context: Context) {
    fun notify(group: SourceGroup, item: MediaItem): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Diag.log(TAG, "not notifying \"${item.title}\": notifications are not allowed")
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.group_news_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            ),
        )
        val open = item.mediaUrl?.let { url ->
            PendingIntent.getActivity(
                context,
                item.id.value.hashCode(),
                Intent(Intent.ACTION_VIEW, url.value.toUri(), context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_new_content)
            .setContentTitle(context.getString(R.string.group_news_title, item.author ?: group.name))
            .setContentText(item.title)
            .setSubText(group.name)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.title))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setGroup("group-news:${group.id.value}")
            .build()
        return runCatching { NotificationManagerCompat.from(context).notify(item.id.value.hashCode(), notification) }
            .onFailure { Diag.warn(TAG, "the notification for \"${item.title}\" could not be posted", it) }
            .isSuccess
    }

    private companion object {
        const val CHANNEL = "group_news"
    }
}

class GroupNewsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as TotumApplication).container
        if (!TotumApplication.warmEngineAfterLaunch) {
            Diag.log(TAG, "group news check skipped: running under instrumentation")
            return Result.success()
        }
        try {
            container.groupNews.check(container.sourceGroupStore.observeGroups().first())
        } catch (cancelled: CancellationException) {
            Diag.log(TAG, "group news check stopped by the system")
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            Diag.warn(TAG, "group news check failed", failure)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "group-news"
        private const val INTERVAL_MINUTES = 60L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GroupNewsWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            Diag.log(TAG, "group news check scheduled every $INTERVAL_MINUTES min on any network")
        }
    }
}

private const val TAG = "group"
