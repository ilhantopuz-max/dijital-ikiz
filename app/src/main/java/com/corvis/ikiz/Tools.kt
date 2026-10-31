package com.corvis.ikiz

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Giden mesaj gibi riskli işlemlerde kullanıcıdan onay almak için. */
interface Host {
    fun confirm(message: String): Boolean
}

/** Modelin çağırabildiği, telefonda çalışan araçlar. */
object Tools {

    private const val ISO = "ISO 8601 yerel saat, örn. 2026-10-31T14:30"

    private fun tool(
        name: String,
        description: String,
        props: List<Triple<String, String, String>>,
        required: List<String>
    ): JSONObject {
        val properties = JSONObject()
        for ((n, type, desc) in props) {
            properties.put(n, JSONObject().put("type", type).put("description", desc))
        }
        val req = JSONArray()
        required.forEach { req.put(it) }
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", req)
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("input_schema", schema)
    }

    fun definitions(): JSONArray {
        val a = JSONArray()
        a.put(
            tool(
                "list_calendar_events",
                "Verilen tarih aralığındaki takvim etkinliklerini ve toplantıları listeler.",
                listOf(
                    Triple("start", "string", "Aralık başlangıcı: $ISO veya yalnızca tarih (yyyy-MM-dd)"),
                    Triple("end", "string", "Aralık sonu: $ISO veya yalnızca tarih; yalnızca tarihse o günün sonunu kapsar")
                ),
                listOf("start", "end")
            )
        )
        a.put(
            tool(
                "create_calendar_event",
                "Takvime etkinlik veya toplantı ekler. Saat bilgisi şart.",
                listOf(
                    Triple("title", "string", "Etkinlik başlığı"),
                    Triple("start", "string", "Başlangıç: $ISO"),
                    Triple("end", "string", "Bitiş: $ISO. Verilmezse 1 saat sürer."),
                    Triple("location", "string", "Yer (isteğe bağlı)"),
                    Triple("description", "string", "Açıklama (isteğe bağlı)"),
                    Triple("reminder_minutes", "integer", "Etkinlikten kaç dakika önce bildirim; varsayılan 10, bildirim istemiyorsa -1")
                ),
                listOf("title", "start")
            )
        )
        a.put(
            tool(
                "create_reminder",
                "Belirli bir zamanda telefona bildirim olarak gelecek hatırlatma kurar.",
                listOf(
                    Triple("text", "string", "Hatırlatılacak şey"),
                    Triple("datetime", "string", "Ne zaman: $ISO")
                ),
                listOf("text", "datetime")
            )
        )
        a.put(tool("list_reminders", "Bekleyen hatırlatmaları listeler.", emptyList(), emptyList()))
        a.put(
            tool(
                "cancel_reminder",
                "Bir hatırlatmayı iptal eder.",
                listOf(Triple("id", "integer", "list_reminders çıktısındaki numara")),
                listOf("id")
            )
        )
        a.put(
            tool(
                "add_note",
                "Kullanıcının notlarına yeni not ekler.",
                listOf(Triple("text", "string", "Not metni")),
                listOf("text")
            )
        )
        a.put(
            tool(
                "list_notes",
                "Notları listeler; query verilirse içinde geçenleri filtreler.",
                listOf(Triple("query", "string", "Aranacak kelime (isteğe bağlı)")),
                emptyList()
            )
        )
        a.put(
            tool(
                "delete_note",
                "Bir notu siler.",
                listOf(Triple("id", "integer", "list_notes çıktısındaki numara")),
                listOf("id")
            )
        )
        a.put(
            tool(
                "find_contact",
                "Rehberde kişi arar; telefon ve e-postalarını döndürür.",
                listOf(Triple("name", "string", "Kişi adı")),
                listOf("name")
            )
        )
        a.put(
            tool(
                "send_sms",
                "SMS gönderir. Göndermeden önce uygulama kullanıcıdan onay alır.",
                listOf(
                    Triple("to", "string", "Rehberdeki kişi adı veya telefon numarası"),
                    Triple("message", "string", "Mesaj metni")
                ),
                listOf("to", "message")
            )
        )
        a.put(
            tool(
                "send_whatsapp",
                "WhatsApp'ı hazır mesajla açar; kullanıcının Gönder'e basması gerekir (mesaj kendiliğinden gitmez).",
                listOf(
                    Triple("to", "string", "Rehberdeki kişi adı veya telefon numarası"),
                    Triple("message", "string", "Mesaj metni")
                ),
                listOf("to", "message")
            )
        )
        a.put(
            tool(
                "send_email",
                "E-posta uygulamasında hazır taslak açar; kullanıcının Gönder'e basması gerekir (mail kendiliğinden gitmez).",
                listOf(
                    Triple("to", "string", "Rehberdeki kişi adı veya e-posta adresi"),
                    Triple("subject", "string", "Konu"),
                    Triple("body", "string", "Mesaj metni")
                ),
                listOf("to", "subject", "body")
            )
        )
        a.put(
            tool(
                "call_contact",
                "Telefon uygulamasını numara girilmiş şekilde açar; arama için kullanıcının tuşa basması gerekir.",
                listOf(Triple("to", "string", "Rehberdeki kişi adı veya telefon numarası")),
                listOf("to")
            )
        )
        a.put(
            tool(
                "save_memory",
                "Kullanıcı hakkında kalıcı, işe yarar bir bilgiyi hafızaya yazar (tercihler, yakınlarının adları, alışkanlıklar). Geçici ayrıntıları kaydetme.",
                listOf(Triple("fact", "string", "Kısa, tek cümlelik bilgi")),
                listOf("fact")
            )
        )
        a.put(
            tool(
                "forget_memory",
                "İçinde verilen ifade geçen hafıza kayıtlarını siler.",
                listOf(Triple("contains", "string", "Silinecek kayıtlarda geçen ifade")),
                listOf("contains")
            )
        )
        a.put(
            tool(
                "search_cards",
                "Kartvizit kayıtlarında arama yapar (firma, kişi, ünvan, telefon, e-posta, web, adres ve notlarda). Boş query tüm kartvizitleri listeler. Sonuçtaki #numara diğer kartvizit araçlarında kullanılır.",
                listOf(Triple("query", "string", "Aranacak kelime(ler); boş bırakılabilir")),
                emptyList()
            )
        )
        a.put(
            tool(
                "add_card",
                "Fotoğraf olmadan yeni kartvizit kaydı ekler. Firma veya kişi adından en az biri şart.",
                listOf(
                    Triple("company", "string", "Firma adı"),
                    Triple("name", "string", "Kişi adı soyadı"),
                    Triple("title", "string", "Ünvan"),
                    Triple("phone", "string", "Telefon"),
                    Triple("email", "string", "E-posta"),
                    Triple("website", "string", "Web sitesi"),
                    Triple("address", "string", "Adres"),
                    Triple("note", "string", "Not")
                ),
                emptyList()
            )
        )
        a.put(
            tool(
                "update_card",
                "Bir kartvizitin verilen alanlarını düzeltir. Yalnızca değişecek alanları ver.",
                listOf(
                    Triple("id", "integer", "search_cards çıktısındaki numara"),
                    Triple("company", "string", "Yeni firma adı"),
                    Triple("name", "string", "Yeni kişi adı"),
                    Triple("title", "string", "Yeni ünvan"),
                    Triple("phone", "string", "Yeni telefon"),
                    Triple("email", "string", "Yeni e-posta"),
                    Triple("website", "string", "Yeni web sitesi"),
                    Triple("address", "string", "Yeni adres")
                ),
                listOf("id")
            )
        )
        a.put(
            tool(
                "add_card_note",
                "Bir kartvizite not ekler (varsayılan: mevcut notun altına tarihle ekler). replace true ise notu tamamen değiştirir.",
                listOf(
                    Triple("id", "integer", "search_cards çıktısındaki numara"),
                    Triple("text", "string", "Not metni"),
                    Triple("replace", "boolean", "true: mevcut notu silip yerine yaz")
                ),
                listOf("id", "text")
            )
        )
        a.put(
            tool(
                "delete_card",
                "Bir kartviziti siler. Silmeden önce uygulama kullanıcıdan onay alır.",
                listOf(Triple("id", "integer", "search_cards çıktısındaki numara")),
                listOf("id")
            )
        )
        return a
    }

    fun execute(ctx: Context, host: Host, name: String, input: JSONObject): String =
        try {
            when (name) {
                "list_calendar_events" -> listEvents(ctx, input)
                "create_calendar_event" -> createEvent(ctx, input)
                "create_reminder" -> createReminder(ctx, input)
                "list_reminders" -> listReminders(ctx)
                "cancel_reminder" ->
                    if (Store.cancelReminder(ctx, input.getInt("id"))) "Hatırlatma iptal edildi." else "Bu numarada hatırlatma yok."
                "add_note" -> {
                    val id = Store.addNote(ctx, input.getString("text"))
                    "Not kaydedildi (#$id)."
                }
                "list_notes" -> listNotes(ctx, input.optString("query", ""))
                "delete_note" ->
                    if (Store.deleteNote(ctx, input.getInt("id"))) "Not silindi." else "Bu numarada not yok."
                "find_contact" -> findContact(ctx, input.getString("name"))
                "send_sms" -> sendSms(ctx, host, input)
                "send_whatsapp" -> sendWhatsapp(ctx, input)
                "send_email" -> sendEmail(ctx, input)
                "call_contact" -> callContact(ctx, input)
                "save_memory" -> {
                    Store.addMemory(ctx, input.getString("fact"))
                    "Hafızaya kaydedildi."
                }
                "forget_memory" -> {
                    val n = Store.forgetMemory(ctx, input.getString("contains"))
                    "$n kayıt silindi."
                }
                "search_cards" -> searchCards(ctx, input.optString("query", ""))
                "add_card" -> addCard(ctx, input)
                "update_card" -> updateCard(ctx, input)
                "add_card_note" -> addCardNote(ctx, input)
                "delete_card" -> deleteCard(ctx, host, input.getInt("id"))
                else -> "Bilinmeyen araç: $name"
            }
        } catch (e: SecurityException) {
            "İzin hatası: ${e.message}"
        } catch (e: Exception) {
            "Hata: ${e.javaClass.simpleName}: ${e.message}"
        }

    // ---------------------------------------------------------------- yardımcılar

    private fun has(ctx: Context, perm: String): Boolean =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun parseTime(raw: String): Long? {
        val s = raw.trim().replace(Regex("(Z|[+-]\\d{2}:?\\d{2})$"), "")
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd"
        )
        for (p in patterns) {
            try {
                val f = SimpleDateFormat(p, Locale.US)
                f.isLenient = false
                val d = f.parse(s)
                if (d != null) return d.time
            } catch (e: Exception) {
                // sıradaki desen
            }
        }
        return null
    }

    private fun fmt(ms: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale("tr")).format(Date(ms))

    private fun fmtUtc(ms: Long, pattern: String): String {
        val f = SimpleDateFormat(pattern, Locale("tr"))
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    private fun launch(ctx: Context, intent: Intent): Boolean =
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }

    // ---------------------------------------------------------------- takvim

    private fun listEvents(ctx: Context, input: JSONObject): String {
        if (!has(ctx, Manifest.permission.READ_CALENDAR)) {
            return "Takvim okuma izni verilmemiş. Uygulama izinlerinden Takvim iznini açması gerekiyor."
        }
        val startS = input.getString("start")
        val endS = input.getString("end")
        val start = parseTime(startS) ?: return "Başlangıç tarihi anlaşılamadı."
        var end = parseTime(endS) ?: return "Bitiş tarihi anlaşılamadı."
        if (endS.trim().length <= 10) end += 86_400_000L

        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, start)
        ContentUris.appendId(b, end)
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY
        )
        val lines = ArrayList<String>()
        ctx.contentResolver.query(
            b.build(), proj, null, null, "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext() && lines.size < 40) {
                val title = c.getString(0) ?: "(başlıksız)"
                val begin = c.getLong(1)
                val endMs = c.getLong(2)
                val loc = c.getString(3)
                val allDay = c.getInt(4) == 1
                val whenStr = if (allDay) {
                    fmtUtc(begin, "d MMMM EEEE") + " (tüm gün)"
                } else {
                    fmt(begin, "d MMMM EEEE HH:mm") + "–" + fmt(endMs, "HH:mm")
                }
                val place = if (!loc.isNullOrBlank()) " ($loc)" else ""
                lines.add("• $whenStr $title$place")
            }
        }
        return if (lines.isEmpty()) "Bu aralıkta etkinlik yok." else lines.joinToString("\n")
    }

    private fun pickCalendarId(ctx: Context): Long? {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT
        )
        val sel = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= " +
            "${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND " +
            "${CalendarContract.Calendars.VISIBLE} = 1"
        var first: Long? = null
        var primary: Long? = null
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null)
            ?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (first == null) first = id
                    if (primary == null && c.getString(1) == c.getString(2)) primary = id
                }
            }
        return primary ?: first
    }

    private fun createEvent(ctx: Context, input: JSONObject): String {
        if (!has(ctx, Manifest.permission.WRITE_CALENDAR) || !has(ctx, Manifest.permission.READ_CALENDAR)) {
            return "Takvim yazma izni verilmemiş. Uygulama izinlerinden Takvim iznini açması gerekiyor."
        }
        val title = input.getString("title")
        val startS = input.getString("start")
        if (startS.trim().length <= 10) return "Saat bilgisi eksik; etkinliğin saatini kullanıcıya sor."
        val start = parseTime(startS) ?: return "Başlangıç zamanı anlaşılamadı."
        val endS = input.optString("end", "")
        var end = if (endS.isNotBlank()) (parseTime(endS) ?: (start + 3_600_000L)) else start + 3_600_000L
        if (end <= start) end = start + 3_600_000L

        val calId = pickCalendarId(ctx)
            ?: return "Cihazda yazılabilir takvim bulunamadı. Telefona bir Google hesabı veya takvim eklenmeli."

        val v = ContentValues()
        v.put(CalendarContract.Events.CALENDAR_ID, calId)
        v.put(CalendarContract.Events.TITLE, title)
        v.put(CalendarContract.Events.DTSTART, start)
        v.put(CalendarContract.Events.DTEND, end)
        v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        val desc = input.optString("description", "")
        if (desc.isNotBlank()) v.put(CalendarContract.Events.DESCRIPTION, desc)
        val loc = input.optString("location", "")
        if (loc.isNotBlank()) v.put(CalendarContract.Events.EVENT_LOCATION, loc)

        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
            ?: return "Etkinlik eklenemedi."

        val minutes = input.optInt("reminder_minutes", 10)
        if (minutes >= 0) {
            try {
                val rv = ContentValues()
                rv.put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(uri))
                rv.put(CalendarContract.Reminders.MINUTES, minutes)
                rv.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, rv)
            } catch (e: Exception) {
                // bildirim eklenemese de etkinlik oluştu
            }
        }
        return "Etkinlik eklendi: $title, ${fmt(start, "d MMMM EEEE HH:mm")}."
    }

    // ---------------------------------------------------------------- hatırlatma ve notlar

    private fun createReminder(ctx: Context, input: JSONObject): String {
        val text = input.getString("text")
        val whenMs = parseTime(input.getString("datetime")) ?: return "Zaman anlaşılamadı."
        if (whenMs <= System.currentTimeMillis()) return "Bu zaman geçmişte kalmış; ileri bir zaman gerekiyor."
        val id = Store.addReminder(ctx, text, whenMs)
        return "Hatırlatma kuruldu (#$id): $text, ${fmt(whenMs, "d MMMM EEEE HH:mm")}."
    }

    private fun listReminders(ctx: Context): String {
        val list = Store.reminders(ctx)
        if (list.isEmpty()) return "Bekleyen hatırlatma yok."
        return list.joinToString("\n") { "#${it.id} ${fmt(it.whenMs, "d MMMM EEEE HH:mm")} ${it.text}" }
    }

    private fun listNotes(ctx: Context, query: String): String {
        val q = norm(query)
        val all = Store.notes(ctx).filter { q.isBlank() || norm(it.getString("text")).contains(q) }
        if (all.isEmpty()) return "Not bulunamadı."
        return all.takeLast(30).joinToString("\n") {
            "#${it.getInt("id")} (${fmt(it.getLong("ts"), "d MMM HH:mm")}) ${it.getString("text")}"
        }
    }

    // ---------------------------------------------------------------- rehber

    private class Person(val name: String, val phones: MutableSet<String>, val emails: MutableSet<String>)

    private class Resolved(val value: String?, val label: String, val error: String? = null)

    private fun norm(s: String): String =
        s.lowercase(Locale("tr"))
            .replace('ı', 'i').replace('ş', 's').replace('ç', 'c')
            .replace('ğ', 'g').replace('ö', 'o').replace('ü', 'u')

    private fun digits(s: String): String = s.filter { it.isDigit() }.takeLast(10)

    private fun loadContacts(ctx: Context): List<Person> {
        val map = LinkedHashMap<Long, Person>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val p = map.getOrPut(c.getLong(0)) {
                    Person(c.getString(1) ?: "", mutableSetOf(), mutableSetOf())
                }
                c.getString(2)?.let { p.phones.add(it) }
            }
        }
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Email.ADDRESS
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val p = map.getOrPut(c.getLong(0)) {
                    Person(c.getString(1) ?: "", mutableSetOf(), mutableSetOf())
                }
                c.getString(2)?.let { p.emails.add(it) }
            }
        }
        return map.values.toList()
    }

    private fun findPeople(ctx: Context, q: String): List<Person> {
        val tokens = norm(q).split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        return loadContacts(ctx).filter { p ->
            val n = norm(p.name)
            tokens.all { n.contains(it) }
        }
    }

    private fun findContact(ctx: Context, name: String): String {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) {
            return "Rehber izni verilmemiş. Uygulama izinlerinden Kişiler iznini açması gerekiyor."
        }
        val people = findPeople(ctx, name)
        if (people.isEmpty()) return "Rehberde '$name' bulunamadı."
        return people.take(8).joinToString("\n") { p ->
            val tel = if (p.phones.isEmpty()) "-" else p.phones.joinToString(", ")
            val mail = if (p.emails.isEmpty()) "-" else p.emails.joinToString(", ")
            "${p.name}: tel $tel; e-posta $mail"
        }
    }

    private fun cardValues(c: Card, email: Boolean): List<String> =
        if (email) {
            listOf(c.email.trim()).filter { it.isNotEmpty() }
        } else {
            c.phone.split(Regex("[,;\\n]")).map { it.trim() }.filter { it.isNotEmpty() }
        }

    private fun resolve(ctx: Context, to: String, email: Boolean): Resolved {
        val t = to.trim()
        if (email && t.contains("@")) return Resolved(t, t)
        if (!email && Regex("^[+0-9 ()\\-]{7,}$").matches(t)) return Resolved(t, t)

        val kind = if (email) "e-posta" else "telefon"
        val found = LinkedHashMap<String, Pair<String, MutableList<String>>>()
        fun addCand(label: String, values: Collection<String>) {
            if (values.isEmpty() || label.isBlank()) return
            val e = found.getOrPut(norm(label)) { Pair(label, mutableListOf<String>()) }
            e.second.addAll(values)
        }

        if (has(ctx, Manifest.permission.READ_CONTACTS)) {
            findPeople(ctx, t).forEach { p -> addCand(p.name, if (email) p.emails else p.phones) }
        }
        val tokens = norm(t).split(" ").filter { it.isNotBlank() }
        if (tokens.isNotEmpty()) {
            for (c in CardStore.all(ctx)) {
                val hay = norm(c.name + " " + c.company)
                if (tokens.all { hay.contains(it) }) {
                    addCand(c.name.ifBlank { c.company }, cardValues(c, email))
                }
            }
        }

        if (found.isEmpty()) return Resolved(null, t, "Rehberde ve kartvizitlerde '$t' için $kind bulunamadı.")
        if (found.size > 1) {
            return Resolved(null, t, "Birden fazla kişi eşleşti: ${found.values.joinToString(", ") { it.first }}. Hangisi?")
        }
        val (label, vals) = found.values.first()
        val distinct = if (email) vals.distinctBy { it.lowercase() } else vals.distinctBy { digits(it) }
        if (distinct.size > 1) {
            return Resolved(null, label, "$label için birden fazla $kind var: ${distinct.joinToString(", ")}. Hangisi?")
        }
        return Resolved(distinct[0], label)
    }

    // ---------------------------------------------------------------- kartvizit

    private fun cardLine(c: Card): String {
        val sb = StringBuilder("#${c.id}")
        fun add(label: String, v: String) {
            if (v.isNotBlank()) sb.append(" | ").append(label).append(": ").append(v)
        }
        add("Firma", c.company)
        add("Kişi", c.name)
        add("Ünvan", c.title)
        add("Tel", c.phone)
        add("Mail", c.email)
        add("Web", c.website)
        add("Adres", c.address)
        add("Not", c.note.replace("\n", " / "))
        return sb.toString()
    }

    private fun searchCards(ctx: Context, query: String): String {
        val res = CardStore.search(ctx, query)
        if (res.isEmpty()) return "Eşleşen kartvizit yok."
        val head = if (res.size > 15) "${res.size} kayıt bulundu, ilk 15:\n" else ""
        return head + res.take(15).joinToString("\n") { cardLine(it) }
    }

    private fun field(input: JSONObject, key: String): String? =
        if (input.has(key)) input.optString(key, "").trim().takeIf { it.isNotEmpty() } else null

    private fun addCard(ctx: Context, input: JSONObject): String {
        val c = Card(
            company = field(input, "company") ?: "",
            name = field(input, "name") ?: "",
            title = field(input, "title") ?: "",
            phone = field(input, "phone") ?: "",
            email = field(input, "email") ?: "",
            website = field(input, "website") ?: "",
            address = field(input, "address") ?: "",
            note = field(input, "note") ?: ""
        )
        if (c.company.isBlank() && c.name.isBlank()) return "Firma veya kişi adı gerekli."
        val id = CardStore.add(ctx, c)
        return "Kartvizit eklendi (#$id): ${c.label()}."
    }

    private fun updateCard(ctx: Context, input: JSONObject): String {
        val c = CardStore.get(ctx, input.getInt("id")) ?: return "Bu numarada kartvizit yok."
        field(input, "company")?.let { c.company = it }
        field(input, "name")?.let { c.name = it }
        field(input, "title")?.let { c.title = it }
        field(input, "phone")?.let { c.phone = it }
        field(input, "email")?.let { c.email = it }
        field(input, "website")?.let { c.website = it }
        field(input, "address")?.let { c.address = it }
        CardStore.update(ctx, c)
        return "Güncellendi: " + cardLine(c)
    }

    private fun addCardNote(ctx: Context, input: JSONObject): String {
        val c = CardStore.get(ctx, input.getInt("id")) ?: return "Bu numarada kartvizit yok."
        val text = input.getString("text").trim()
        if (text.isEmpty()) return "Not boş olamaz."
        if (input.optBoolean("replace", false)) {
            c.note = text
        } else {
            val stamped = fmt(System.currentTimeMillis(), "d MMM yyyy") + ": " + text
            c.note = if (c.note.isBlank()) stamped else c.note + "\n" + stamped
        }
        CardStore.update(ctx, c)
        return "Not kaydedildi: ${c.label()}."
    }

    private fun deleteCard(ctx: Context, host: Host, id: Int): String {
        val c = CardStore.get(ctx, id) ?: return "Bu numarada kartvizit yok."
        if (!host.confirm("Kartvizit silinsin mi?\n\n${c.label()}")) return "Kullanıcı silmeyi iptal etti."
        CardStore.delete(ctx, id)
        return "Kartvizit silindi: ${c.label()}."
    }

    // ---------------------------------------------------------------- mesajlaşma

    @Suppress("DEPRECATION")
    private fun sendSms(ctx: Context, host: Host, input: JSONObject): String {
        val r = resolve(ctx, input.getString("to"), false)
        val number = r.value ?: return r.error ?: "Alıcı bulunamadı."
        val msg = input.getString("message")

        val ok = host.confirm("SMS gönderilsin mi?\n\nKime: ${r.label} ($number)\n\n$msg")
        if (!ok) return "Kullanıcı gönderimi iptal etti."

        if (has(ctx, Manifest.permission.SEND_SMS)) {
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            val parts = sms.divideMessage(msg)
            sms.sendMultipartTextMessage(number, null, parts, null, null)
            return "SMS gönderildi: ${r.label}."
        }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            .putExtra("sms_body", msg)
        return if (launch(ctx, i)) {
            "SMS izni yok; mesaj uygulamasını hazır mesajla açtım. Kullanıcının Gönder'e basması gerekiyor."
        } else {
            "SMS gönderilemedi: SMS izni yok ve mesaj uygulaması açılamadı."
        }
    }

    private fun waNumber(raw: String): String {
        val plus = raw.trim().startsWith("+")
        var d = raw.filter { it.isDigit() }
        if (!plus) {
            if (d.startsWith("00")) {
                d = d.substring(2)
            } else if (d.startsWith("0")) {
                d = "90" + d.substring(1)
            } else if (d.length == 10) {
                d = "90$d"
            }
        }
        return d
    }

    private fun sendWhatsapp(ctx: Context, input: JSONObject): String {
        val r = resolve(ctx, input.getString("to"), false)
        val number = r.value ?: return r.error ?: "Alıcı bulunamadı."
        val msg = input.getString("message")
        val uri = Uri.parse("https://wa.me/${waNumber(number)}?text=${Uri.encode(msg)}")
        return if (launch(ctx, Intent(Intent.ACTION_VIEW, uri))) {
            "WhatsApp, ${r.label} için hazır mesajla açıldı. Gönder'e basılınca gidecek; henüz gönderilmedi."
        } else {
            "WhatsApp açılamadı."
        }
    }

    private fun sendEmail(ctx: Context, input: JSONObject): String {
        val r = resolve(ctx, input.getString("to"), true)
        val addr = r.value ?: return r.error ?: "Alıcı bulunamadı."
        val i = Intent(Intent.ACTION_SENDTO)
        i.data = Uri.parse("mailto:")
        i.putExtra(Intent.EXTRA_EMAIL, arrayOf(addr))
        i.putExtra(Intent.EXTRA_SUBJECT, input.getString("subject"))
        i.putExtra(Intent.EXTRA_TEXT, input.getString("body"))
        return if (launch(ctx, i)) {
            "E-posta taslağı ${r.label} ($addr) için hazırlandı. Gönder'e basılınca gidecek; henüz gönderilmedi."
        } else {
            "E-posta uygulaması açılamadı."
        }
    }

    private fun callContact(ctx: Context, input: JSONObject): String {
        val r = resolve(ctx, input.getString("to"), false)
        val number = r.value ?: return r.error ?: "Alıcı bulunamadı."
        val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
        return if (launch(ctx, i)) {
            "Telefon uygulaması ${r.label} (${number}) ile açıldı; arama için tuşa basılması gerekiyor."
        } else {
            "Telefon uygulaması açılamadı."
        }
    }
}
