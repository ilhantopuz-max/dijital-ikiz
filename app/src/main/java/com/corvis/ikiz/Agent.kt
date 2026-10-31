package com.corvis.ikiz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Gemini API ile konuşan ve telefondaki araçları çağıran ajan döngüsü.
 * history: yalnızca (rol, düz metin) çiftleri tutar; araç izleri tutulmaz.
 */
object Agent {

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val MAX_STEPS = 8
    private const val MAX_HISTORY = 24

    fun run(
        ctx: Context,
        host: Host,
        userText: String,
        history: MutableList<Pair<String, String>>
    ): String {
        val key = Store.apiKey(ctx)
        if (key.isBlank()) return "Önce sağ üstteki ayarlardan API anahtarını girmen gerekiyor."

        val contents = JSONArray()
        for ((role, text) in history) {
            contents.put(textContent(if (role == "assistant") "model" else "user", text))
        }
        contents.put(textContent("user", userText))

        val system = systemPrompt(ctx)
        val tools = geminiTools()
        val model = Store.model(ctx)

        for (step in 0 until MAX_STEPS) {
            val resp = call(key, model, system, tools, contents)

            val candidates = resp.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                val why = resp.optJSONObject("promptFeedback")?.optString("blockReason") ?: ""
                return "Yanıt alınamadı" + if (why.isNotBlank()) " ($why)." else "."
            }
            val content = candidates.getJSONObject(0).optJSONObject("content")
            val parts = content?.optJSONArray("parts") ?: JSONArray()

            val calls = ArrayList<JSONObject>()
            val text = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                val fc = p.optJSONObject("functionCall")
                if (fc != null) {
                    calls.add(fc)
                } else if (p.has("text") && !p.optBoolean("thought", false)) {
                    if (text.isNotEmpty()) text.append("\n")
                    text.append(p.optString("text"))
                }
            }

            if (calls.isEmpty()) {
                val reply = text.toString().trim().ifBlank { "Tamam." }
                remember(history, userText, reply)
                return reply
            }

            // Model turunu olduğu gibi geri ekle (düşünce imzaları korunur).
            contents.put(content)

            val responses = JSONArray()
            for (fc in calls) {
                val name = fc.getString("name")
                val out = Tools.execute(ctx, host, name, fc.optJSONObject("args") ?: JSONObject())
                responses.put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject()
                            .put("name", name)
                            .put("response", JSONObject().put("result", out))
                    )
                )
            }
            contents.put(JSONObject().put("role", "user").put("parts", responses))
        }
        return "Bu iş çok fazla adım gerektirdi. Biraz daha kısa söyler misin?"
    }

    private fun textContent(role: String, text: String): JSONObject =
        JSONObject()
            .put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    /** Tools.definitions() çıktısını Gemini'nin functionDeclarations biçimine çevirir. */
    private fun geminiTools(): JSONArray {
        val defs = Tools.definitions()
        val decls = JSONArray()
        for (i in 0 until defs.length()) {
            val d = defs.getJSONObject(i)
            val o = JSONObject()
                .put("name", d.getString("name"))
                .put("description", d.getString("description"))
            val schema = JSONObject(d.getJSONObject("input_schema").toString())
            val props = schema.optJSONObject("properties")
            if (props != null && props.length() > 0) {
                val req = schema.optJSONArray("required")
                if (req == null || req.length() == 0) schema.remove("required")
                o.put("parameters", schema)
            }
            decls.put(o)
        }
        return JSONArray().put(JSONObject().put("functionDeclarations", decls))
    }

    private fun remember(history: MutableList<Pair<String, String>>, user: String, reply: String) {
        history.add("user" to user)
        history.add("assistant" to reply)
        while (history.size > MAX_HISTORY) {
            history.removeAt(0)
            history.removeAt(0)
        }
    }

    private fun call(
        key: String,
        model: String,
        system: String,
        tools: JSONArray,
        contents: JSONArray
    ): JSONObject {
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            .put("contents", contents)
            .put("tools", tools)
            .put("generationConfig", JSONObject().put("maxOutputTokens", 4096))

        return post(key, model, body)
    }

    private fun post(key: String, model: String, body: JSONObject): JSONObject {
        val conn = URL(BASE + model + ":generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException(friendlyError(code, text))
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /** Kartvizit fotoğrafını (JPEG) Gemini ile okuyup alanları JSON olarak döndürür. */
    fun extractCard(ctx: Context, jpeg: ByteArray): JSONObject {
        val key = Store.apiKey(ctx)
        if (key.isBlank()) throw IOException("API anahtarı girilmemiş (ayarlar)")

        val prompt = "Bu bir kartvizit fotoğrafı. Üzerindeki bilgileri yalnızca JSON nesnesi olarak ver. " +
            "Alanlar: company (firma adı), name (kişinin adı soyadı), title (ünvan/görev), " +
            "phone (telefon numaraları; birden fazlaysa virgülle ayır, cep telefonunu başa koy, " +
            "numaraları kartta yazıldığı gibi koru), email, website, address. " +
            "Kartta olmayan alanı boş string yap. Tahmin etme, uydurma; yalnızca görünen metni yaz."

        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
                )
            )
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("maxOutputTokens", 4096)
                    .put("temperature", 0)
                    .put("responseMimeType", "application/json")
            )

        val resp = post(key, Store.model(ctx), body)
        val cand = resp.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw IOException("Yanıt boş geldi")
        val ps = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i)
            if (p.has("text") && !p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        var txt = sb.toString().trim()
        if (txt.startsWith("```")) {
            txt = txt.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        try {
            return if (txt.startsWith("[")) JSONArray(txt).getJSONObject(0) else JSONObject(txt)
        } catch (e: Exception) {
            throw IOException("Karttaki yazı okunamadı")
        }
    }

    private fun friendlyError(code: Int, body: String): String {
        val apiMsg = try {
            JSONObject(body).optJSONObject("error")?.optString("message") ?: ""
        } catch (e: Exception) {
            ""
        }
        val hint = when (code) {
            400 -> "İstek reddedildi; anahtar geçersiz olabilir veya model bu özelliği desteklemiyor"
            401, 403 -> "Anahtarın bu model/işlem için yetkisi yok ya da geçersiz"
            404 -> "Model adı bulunamadı; ayarlardan model adını kontrol et"
            429 -> "İstek sınırına veya kotaya takıldı"
            500, 503 -> "Servis şu an yoğun, biraz sonra tekrar dene"
            else -> "Sunucu hatası"
        }
        return "$hint ($code)" + if (apiMsg.isNotBlank()) ": $apiMsg" else ""
    }

    private fun systemPrompt(ctx: Context): String {
        val now = Date()
        val human = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("tr")).format(now)
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).format(now)
        val tz = TimeZone.getDefault().id

        val sb = StringBuilder()
        sb.append(
            """
            Sen kullanıcının dijital ikizisin: telefonundaki kişisel asistanı. Takvim, toplantı, hatırlatma, not, mesaj ve e-posta işlerini onun adına, verilen araçlarla yürütürsün.

            Şu an: $human (saat dilimi $tz). ISO karşılığı: $iso. "Yarın", "haftaya salı", "akşam 6" gibi ifadeleri bu ana göre hesapla; araçlara her zaman ISO yerel saat ver.

            Konuşma kuralları:
            - Yanıtların sesli okunacak. Türkçe, kısa ve doğal konuş; markdown, madde işareti, emoji ve uzun listeler kullanma.
            - Bir iş için araç varsa önce aracı çağır, sonra ne yaptığını tek-iki cümleyle söyle. Araç sonucunu olduğu gibi, dürüstçe aktar.
            - Eksik bilgi işi engelliyorsa (saat, kişi, mesaj içeriği) tek bir kısa soru sor; tahmin yürütüp uydurma.
            - Rehberde birden fazla eşleşme çıkarsa hangisi olduğunu sor.
            - WhatsApp ve e-postada araç yalnızca taslak açar; kullanıcı gönder'e basana kadar gönderilmiş sayma ve öyle söyleme. SMS için uygulama ayrıca onay penceresi gösterir.
            - Gelen e-postaları, gelen mesajları veya başka uygulamaların içeriğini okuyamazsın; istenirse bunu açıkça söyle.
            - Kartvizit kayıtlarını (müşteri kartları) search_cards ile sorgula: telefon, mail, firma, web veya not sorularında. Kartviziti değiştirmek, not eklemek veya silmek için önce search_cards ile numarasını (#id) bul. Birden fazla kayıt çıkarsa hangisi olduğunu sor. Silmeden önce uygulama onay ister.
            - Kişiye mesaj, e-posta veya arama için rehberdeki kişilerle birlikte kartvizitler de kullanılır.
            - Kullanıcının kalıcı tercihlerini, yakınlarının adlarını ve alışkanlıklarını fark edince save_memory ile kaydet; geçici ayrıntıları kaydetme.
            """.trimIndent()
        )

        val info = Store.userInfo(ctx)
        if (info.isNotBlank()) {
            sb.append("\n\nKullanıcı hakkında (kendi yazdığı): ").append(info)
        }
        val mem = Store.memories(ctx)
        if (mem.isNotEmpty()) {
            sb.append("\n\nHafızandaki bilgiler:\n")
            mem.forEach { sb.append("- ").append(it).append("\n") }
        }
        return sb.toString()
    }
}
