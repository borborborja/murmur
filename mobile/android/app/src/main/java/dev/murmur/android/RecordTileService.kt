package dev.murmur.android

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

class RecordTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = InternalPlatformActions.intent(this, "record.start").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        else startOnLegacyAndroid(intent)
    }

    /** The PendingIntent overload starts at API 34; API 29-33 need the Intent overload. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startOnLegacyAndroid(intent: Intent) {
        startActivityAndCollapse(intent)
    }
}
