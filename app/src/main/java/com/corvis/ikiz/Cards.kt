package com.corvis.ikiz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class Card(
    var id: Int = 0,
    var company: String = "",
    var name: String = "",
    var title: String = "",
    var phone: String = "",
    var email: String = "",
    var website: String = "",
    var address: String = "",
    var note: String = "",
    var photo: String = "",
    var ts: Long = 0L
) {
    fun label(): String =
        listOf(company, name).filter { it.isNotBlank() }.joinToString(" – ")
}

/** Türkçe karakterleri sadeleştirip küçük harfe çevirir (arama için). */
fun normTr(s: String): String =
    s.lowercase(Locale("tr"))
        .replace('ı', 'i').replace('ş', 's').replace('ç', 'c')
        .replace('ğ', 'g').replace('ö', 'o').replace('ü', 'u')

/** Kartvizit kayıtları. Metin SharedPreferences'ta, fotoğraflar filesDir/cards altında. */
object CardStore {

    private const val KEY = "cards"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("ikiz", Context.MODE_PRIVATE)

    fun photoFile(ctx: Context, name: String): File =
        File(File(ctx.filesDir, "cards"), name)

    private fun toJson(c: Card): JSONObject =
        JSONObject()
            .put("id", c.id)
            .put("company", c.company)
            .put("name", c.name)
            .put("title", c.title)
            .put("phone", c.phone)
            .put("email", c.email)
            .put("website", c.website)
            .put("address", c.address)
            .put("note", c.note)
            .put("photo", c.photo)
            .put("ts", c.ts)

    private fun fromJson(o: JSONObject): Card =
        Card(
            id = o.optInt("id"),
            company = o.optString("company"),
            name = o.optString("name"),
            title = o.optString("title"),
            phone = o.optString("phone"),
            email = o.optString("email"),
            website = o.optString("website"),
            address = o.optString("address"),
            note = o.optString("note"),
            photo = o.optString("photo"),
            ts = o.optLong("ts")
        )

    fun all(ctx: Context): List<Card> {
        val arr = try {
            JSONArray(prefs(ctx).getString(KEY, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        val out = ArrayList<Card>()
        for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
        return out
    }

    private fun save(ctx: Context, list: List<Card>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun get(ctx: Context, id: Int): Card? = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun add(ctx: Context, c: Card): Int {
        c.id = Store.nextId(ctx)
        c.ts = System.currentTimeMillis()
        save(ctx, all(ctx) + c)
        return c.id
    }

    @Synchronized
    fun update(ctx: Context, c: Card): Boolean {
        val list = all(ctx)
        if (list.none { it.id == c.id }) return false
        save(ctx, list.map { if (it.id == c.id) c else it })
        return true
    }

    @Synchronized
    fun delete(ctx: Context, id: Int): Boolean {
        val list = all(ctx)
        val target = list.firstOrNull { it.id == id } ?: return false
        if (target.photo.isNotBlank()) photoFile(ctx, target.photo).delete()
        save(ctx, list.filter { it.id != id })
        return true
    }

    /** Tüm alanlarda ve notlarda arar; her kelime bir yerde geçmeli. */
    fun search(ctx: Context, query: String): List<Card> {
        val tokens = normTr(query).split(" ").filter { it.isNotBlank() }
        val list = all(ctx)
        if (tokens.isEmpty()) return list
        return list.filter { c ->
            val hay = normTr(
                listOf(c.company, c.name, c.title, c.phone, c.email, c.website, c.address, c.note)
                    .joinToString(" ")
            )
            tokens.all { hay.contains(it) }
        }
    }
}
