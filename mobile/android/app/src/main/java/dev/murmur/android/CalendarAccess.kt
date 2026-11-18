package dev.murmur.android

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

object CalendarAccess {
    fun events(context: Context): List<JSONObject> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val settings = AppSettings(context).read()
        if (!settings.optBoolean("calendarEnabled")) return emptyList()
        val selected = settings.optJSONArray("calendarIds")?.strings()?.toSet()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, now - 24*60*60*1000); ContentUris.appendId(this, now + 14L*24*60*60*1000)
        }.build()
        val columns = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.EVENT_LOCATION)
        val result = mutableListOf<JSONObject>()
        context.contentResolver.query(uri, columns, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val calendarId = cursor.getString(1)
                if (selected != null && calendarId !in selected) continue
                val guests = JSONArray()
                context.contentResolver.query(CalendarContract.Attendees.CONTENT_URI,
                    arrayOf(CalendarContract.Attendees.ATTENDEE_NAME, CalendarContract.Attendees.ATTENDEE_EMAIL),
                    "${CalendarContract.Attendees.EVENT_ID}=?", arrayOf(cursor.getString(0)), null)?.use { attendees ->
                    while (attendees.moveToNext()) guests.put(attendees.getString(0)?.takeIf(String::isNotBlank) ?: attendees.getString(1).orEmpty())
                }
                val details = "${cursor.getString(5).orEmpty()} ${cursor.getString(6).orEmpty()}"
                val platform = listOf("meet.google.com", "zoom.us", "teams.microsoft.com").firstOrNull(details::contains)
                val event = json("id" to cursor.getString(0), "calendarId" to calendarId,
                    "title" to cursor.getString(2).orEmpty(), "start" to cursor.getLong(3), "end" to cursor.getLong(4),
                    "guests" to guests, "platform" to platform)
                result += event
                if (settings.optBoolean("calendarAlerts") && (guests.length() > 0 || platform != null || settings.optBoolean("calendarNoGuests"))) schedule(context, event)
            }
        }
        return result
    }
    fun calendars(context: Context): List<JSONObject> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val result = mutableListOf<JSONObject>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) result += json("id" to cursor.getString(0), "title" to cursor.getString(1), "account" to cursor.getString(2), "color" to cursor.getInt(3))
        }
        return result
    }
    private fun schedule(context: Context, event: JSONObject) {
        val trigger = event.getLong("start") - 120_000
        if (trigger <= System.currentTimeMillis()) return
        val request = (event.getString("id") + event.getLong("start")).hashCode()
        val intent = Intent(context, CalendarReminderReceiver::class.java).putExtra("event", event.toString())
        val pending = PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
    }
}

class CalendarReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AppSettings(context).read().optBoolean("calendarEnabled")) return
        val event = runCatching { JSONObject(intent.getStringExtra("event") ?: "") }.getOrNull() ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("calendar", context.getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, event.getString("id").hashCode(), Intent(context, MainActivity::class.java).putExtra("calendarEvent", event.toString()), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(event.getString("id").hashCode(), NotificationCompat.Builder(context, "calendar")
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar).setContentTitle(event.optString("title"))
            .setContentText(context.getString(R.string.calendar_reminder)).setContentIntent(open).setAutoCancel(true).build())
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Boot never starts a microphone or a network listener.
        val pending = goAsync()
        Thread { try { CalendarAccess.events(context) } finally { pending.finish() } }.start()
    }
}
