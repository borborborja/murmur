package dev.murmur.android

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("processing", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 1002, NotificationCompat.Builder(this, "processing")
            .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.processing_notification)).setOngoing(true).setSilent(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.launch {
            var idle = 0
            while (isActive) {
                delay(1000)
                idle = if (AppRuntime.get(this@ProcessingService).processor.hasWork()) 0 else idle + 1
                if (idle >= 30) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); break }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onTimeout(startId: Int, fgsType: Int) { AppRuntime.get(this).processor.foregroundStopped(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
