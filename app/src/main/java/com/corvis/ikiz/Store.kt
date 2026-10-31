package com.corvis.ikiz

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Ayarlar, notlar, hatırlatmalar ve "hafıza" için basit yerel depolama. */
object Store {

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("ikiz", Context.MODE_PRIVATE)

    // ---- Ayarlar ----
    fun apiKey(ctx: Context): String = prefs(ctx).getString("api_key", "") ?: ""
    fun model(ctx: Context): String =
        (prefs(ctx).getString("model", "") ?: "").ifBlank { "gemini-2.5-flash" }
    fun userInfo(ctx: Context): String = prefs(ctx).getString("user_info", "") ?: ""

    fun saveSettings(ctx: Context, apiKey: String, model: String, userInfo: String) {
        prefs(ctx).edit()
            .putString("api_key", apiKey.trim())
            .putString("model", model.trim())
            .putString("user_info", userInfo.trim())
            .apply()
    }

    @Synchronized
    fun nextId(ctx: Context): Int {
        val p = prefs(ctx)
        val id = p.getInt("next_id", 1)
        p.edit().putInt("next_id", id + 1).apply()
        return id
    }

    private fun readArray(ctx: Context, key: String): JSONArray =
        try {
            JSONArray(prefs(ctx).getString(key, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }

    private fun writeArray(ctx: Context, key: String, arr: JSONArray) {
        prefs(ctx).edit().putString(key, arr.toString()).apply()
    }

    // ---- Notlar ----
    @Synchronized
    fun addNote(ctx: Context, text: String): Int {
        val id = nextId(ctx)
        val arr = readArray(ctx, "notes")
        arr.put(
            JSONObject().put("id", id).put("text", text).put("ts", System.currentTimeMillis())
        )
        writeArray(ctx, "notes", arr)
        return id
    }

    fun notes(ctx: Context): List<JSONObject> {
        val arr = readArray(ctx, "notes")
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
        return out
    }

    @Synchronized
    fun deleteNote(ctx: Context, id: Int): Boolean {
        val old = readArray(ctx, "notes")
        val fresh = JSONArray()
        var found = false
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            if (o.getInt("id") == id) found = true else fresh.put(o)
        }
        if (found) writeArray(ctx, "notes", fresh)
        return found
    }

    // ---- Hafıza (kullanıcı hakkında kalıcı bilgiler) ----
    fun memories(ctx: Context): List<String> {
        val arr = readArray(ctx, "memory")
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) out.add(arr.getString(i))
        return out
    }

    @Synchronized
    fun addMemory(ctx: Context, fact: String) {
        val list = ArrayList(memories(ctx))
        list.add(fact.trim())
        while (list.size > 60) list.removeAt(0)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        writeArray(ctx, "memory", arr)
    }

    @Synchronized
    fun forgetMemory(ctx: Context, contains: String): Int {
        val needle = contains.trim().lowercase()
        val list = memories(ctx)
        val keep = list.filter { !it.lowercase().contains(needle) }
        val arr = JSONArray()
        keep.forEach { arr.put(it) }
        writeArray(ctx, "memory", arr)
        return list.size - keep.size
    }

    // ---- Hatırlatmalar ----
    class Reminder(val id: Int, val text: String, val whenMs: Long)

    fun reminders(ctx: Context): List<Reminder> {
        val arr = readArray(ctx, "reminders")
        val out = ArrayList<Reminder>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Reminder(o.getInt("id"), o.getString("text"), o.getLong("when")))
        }
        return out.sortedBy { it.whenMs }
    }

    private fun saveReminders(ctx: Context, list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("text", it.text).put("when", it.whenMs))
        }
        writeArray(ctx, "reminders", arr)
    }

    private fun alarmIntent(ctx: Context, id: Int, text: String): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java)
            .putExtra("id", id)
            .putExtra("text", text)
        return PendingIntent.getBroadcast(
            ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx, r.id, r.text)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.whenMs, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.whenMs, pi)
        }
    }

    @Synchronized
    fun addReminder(ctx: Context, text: String, whenMs: Long): Int {
        val id = nextId(ctx)
        val r = Reminder(id, text, whenMs)
        saveReminders(ctx, reminders(ctx) + r)
        schedule(ctx, r)
        return id
    }

    @Synchronized
    fun cancelReminder(ctx: Context, id: Int): Boolean {
        val list = reminders(ctx)
        val target = list.firstOrNull { it.id == id } ?: return false
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx, target.id, target.text)
        am.cancel(pi)
        pi.cancel()
        saveReminders(ctx, list.filter { it.id != id })
        return true
    }

    @Synchronized
    fun removeReminderRecord(ctx: Context, id: Int) {
        saveReminders(ctx, reminders(ctx).filter { it.id != id })
    }

    @Synchronized
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val future = reminders(ctx).filter { it.whenMs > now }
        saveReminders(ctx, future)
        future.forEach { schedule(ctx, it) }
    }
}
