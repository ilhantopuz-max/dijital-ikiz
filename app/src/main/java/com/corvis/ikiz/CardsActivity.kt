package com.corvis.ikiz

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.max

class CardsActivity : Activity() {

    private val bg = Color.parseColor("#0B1220")
    private val rowColor = Color.parseColor("#1F2937")
    private val accent = Color.parseColor("#4F46E5")
    private val danger = Color.parseColor("#B91C1C")

    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private lateinit var info: TextView

    private var pendingCapture: File? = null
    private var dictateTarget: EditText? = null
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        pendingCapture = savedInstanceState?.getString("cap")?.let { File(it) }
        buildUi()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCapture?.let { outState.putString("cap", it.path) }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    // ------------------------------------------------------------------ arayüz

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.setPadding(dp(14), dp(10), dp(14), dp(10))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val back = TextView(this)
        back.text = "‹ Geri"
        back.textSize = 16f
        back.setTextColor(Color.WHITE)
        back.setPadding(0, dp(6), dp(14), dp(6))
        back.setOnClickListener { finish() }
        header.addView(back, LinearLayout.LayoutParams(WRAP, WRAP))
        val title = TextView(this)
        title.text = "Kartvizitler"
        title.textSize = 22f
        title.setTextColor(Color.WHITE)
        title.setTypeface(title.typeface, Typeface.BOLD)
        header.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        // Arama satırı
        val searchRow = LinearLayout(this)
        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.gravity = Gravity.CENTER_VERTICAL
        search = EditText(this)
        search.hint = "Ara: firma, kişi, not…"
        search.setHintTextColor(Color.parseColor("#6B7280"))
        search.setTextColor(Color.WHITE)
        search.setSingleLine(true)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refresh()
            }
        })
        searchRow.addView(search, LinearLayout.LayoutParams(0, WRAP, 1f))
        val micBtn = Button(this)
        micBtn.text = "🎤"
        micBtn.setOnClickListener { dictate(null) }
        searchRow.addView(micBtn, LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(searchRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // Ekleme düğmeleri
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        fun action(label: String, onClick: () -> Unit) {
            val b = Button(this)
            b.text = label
            b.isAllCaps = false
            b.setOnClickListener { onClick() }
            actions.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        action("📷 Fotoğraf çek") { capture() }
        action("🖼 Galeriden") { pickFromGallery() }
        action("＋ Elle") { showForm(Card(), true) }
        root.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP))

        info = TextView(this)
        info.textSize = 13f
        info.setTextColor(Color.parseColor("#9CA3AF"))
        info.setPadding(0, dp(8), 0, dp(4))
        root.addView(info, LinearLayout.LayoutParams(MATCH, WRAP))

        val scroll = ScrollView(this)
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list, ViewGroup.LayoutParams(MATCH, WRAP))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        setContentView(root)
    }

    private fun refresh() {
        val q = search.text.toString()
        val cards = if (q.isBlank()) {
            CardStore.all(this).sortedBy { normTr(it.company.ifBlank { it.name }) }
        } else {
            CardStore.search(this, q)
        }
        info.text = if (q.isBlank()) "${cards.size} kartvizit" else "${cards.size} sonuç"
        list.removeAllViews()
        if (cards.isEmpty()) {
            val empty = TextView(this)
            empty.text = if (q.isBlank()) {
                "Henüz kartvizit yok. Fotoğraf çekerek ilkini ekle."
            } else {
                "Eşleşen kartvizit yok."
            }
            empty.setTextColor(Color.parseColor("#9CA3AF"))
            empty.setPadding(0, dp(24), 0, 0)
            list.addView(empty)
            return
        }
        for (c in cards) list.addView(rowFor(c))
    }

    private fun rowFor(c: Card): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(dp(14), dp(10), dp(14), dp(10))
        val shape = GradientDrawable()
        shape.cornerRadius = dp(14).toFloat()
        shape.setColor(rowColor)
        row.background = shape

        fun line(text: String, size: Float, color: String, bold: Boolean = false) {
            if (text.isBlank()) return
            val tv = TextView(this)
            tv.text = text
            tv.textSize = size
            tv.setTextColor(Color.parseColor(color))
            if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
            row.addView(tv)
        }

        line(c.company.ifBlank { c.name }, 17f, "#FFFFFF", true)
        val who = if (c.company.isNotBlank()) {
            listOf(c.name, c.title).filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            c.title
        }
        line(who, 14f, "#E5E7EB")
        line(listOf(c.phone, c.email).filter { it.isNotBlank() }.joinToString("  ·  "), 13f, "#9CA3AF")
        if (c.note.isNotBlank()) {
            val oneLine = c.note.replace("\n", " ")
            line("📝 " + if (oneLine.length > 70) oneLine.take(70) + "…" else oneLine, 13f, "#A5B4FC")
        }
        row.setOnClickListener { showDetail(c) }

        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(8)
        row.layoutParams = lp
        return row
    }

    // ------------------------------------------------------------------ detay

    private fun firstPhone(c: Card): String =
        c.phone.split(Regex("[,;\\n]")).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            toast("Uygulama açılamadı.")
        }
    }

    private fun showDetail(c: Card) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)

        if (c.photo.isNotBlank()) {
            val f = CardStore.photoFile(this, c.photo)
            if (f.exists()) {
                val opts = BitmapFactory.Options()
                opts.inSampleSize = 2
                val bmp = BitmapFactory.decodeFile(f.path, opts)
                if (bmp != null) {
                    val iv = ImageView(this)
                    iv.setImageBitmap(bmp)
                    iv.adjustViewBounds = true
                    iv.scaleType = ImageView.ScaleType.FIT_CENTER
                    iv.maxHeight = dp(220)
                    box.addView(iv, LinearLayout.LayoutParams(MATCH, WRAP))
                }
            }
        }

        fun fact(label: String, value: String) {
            if (value.isBlank()) return
            val tv = TextView(this)
            tv.text = "$label: $value"
            tv.textSize = 15f
            tv.setPadding(0, dp(6), 0, 0)
            tv.setTextIsSelectable(true)
            box.addView(tv)
        }
        fact("Firma", c.company)
        fact("Kişi", c.name)
        fact("Ünvan", c.title)
        fact("Telefon", c.phone)
        fact("E-posta", c.email)
        fact("Web", c.website)
        fact("Adres", c.address)
        fact("Not", c.note)

        var dlg: AlertDialog? = null

        fun btn(label: String, color: Int?, onClick: () -> Unit): Button {
            val b = Button(this)
            b.text = label
            b.isAllCaps = false
            if (color != null) b.setBackgroundColor(color)
            b.setOnClickListener {
                dlg?.dismiss()
                onClick()
            }
            return b
        }

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        if (firstPhone(c).isNotEmpty()) {
            row1.addView(
                btn("Ara", null) {
                    open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(firstPhone(c)))))
                },
                LinearLayout.LayoutParams(0, WRAP, 1f)
            )
        }
        if (c.email.isNotBlank()) {
            row1.addView(
                btn("E-posta", null) {
                    open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + c.email.trim())))
                },
                LinearLayout.LayoutParams(0, WRAP, 1f)
            )
        }
        if (c.website.isNotBlank()) {
            row1.addView(
                btn("Web", null) {
                    val u = c.website.trim()
                    val url = if (u.startsWith("http", ignoreCase = true)) u else "https://$u"
                    open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                LinearLayout.LayoutParams(0, WRAP, 1f)
            )
        }
        val topMargin = LinearLayout.LayoutParams(MATCH, WRAP)
        topMargin.topMargin = dp(12)
        box.addView(row1, topMargin)

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(
            btn("Düzenle / not ekle", accent) { showForm(c, false) },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        row2.addView(
            btn("Sil", danger) { confirmDelete(c) },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        box.addView(row2, LinearLayout.LayoutParams(MATCH, WRAP))

        val sv = ScrollView(this)
        sv.addView(box)
        dlg = AlertDialog.Builder(this)
            .setTitle(c.label())
            .setView(sv)
            .setNegativeButton("Kapat", null)
            .create()
        dlg?.show()
    }

    private fun confirmDelete(c: Card) {
        AlertDialog.Builder(this)
            .setTitle("Silinsin mi?")
            .setMessage(c.label())
            .setPositiveButton("Sil") { _, _ ->
                CardStore.delete(this, c.id)
                refresh()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ------------------------------------------------------------------ form (ekle / düzenle)

    private fun showForm(card: Card, isNew: Boolean) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)

        fun field(label: String, value: String, type: Int): EditText {
            val tv = TextView(this)
            tv.text = label
            tv.textSize = 12f
            tv.setPadding(0, dp(10), 0, 0)
            box.addView(tv)
            val et = EditText(this)
            et.setText(value)
            et.inputType = type
            box.addView(et, LinearLayout.LayoutParams(MATCH, WRAP))
            return et
        }

        val words = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val company = field("Firma", card.company, words)
        val name = field("Kişi", card.name, words)
        val title = field("Ünvan", card.title, words)
        val phone = field("Telefon", card.phone, InputType.TYPE_CLASS_PHONE)
        val email = field(
            "E-posta", card.email,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        )
        val web = field(
            "Web sitesi", card.website,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        val address = field(
            "Adres", card.address,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        )
        val note = field(
            "Not", card.note,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        )
        val dict = Button(this)
        dict.text = "🎤 Notu sesle yaz"
        dict.isAllCaps = false
        dict.setOnClickListener { dictate(note) }
        box.addView(dict, LinearLayout.LayoutParams(MATCH, WRAP))

        val sv = ScrollView(this)
        sv.addView(box)
        val dlg = AlertDialog.Builder(this)
            .setTitle(if (isNew) "Yeni kartvizit" else "Kartviziti düzenle")
            .setView(sv)
            .setCancelable(false)
            .setPositiveButton("Kaydet", null)
            .setNegativeButton("Vazgeç") { _, _ ->
                if (isNew && card.photo.isNotBlank()) CardStore.photoFile(this, card.photo).delete()
            }
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            card.company = company.text.toString().trim()
            card.name = name.text.toString().trim()
            card.title = title.text.toString().trim()
            card.phone = phone.text.toString().trim()
            card.email = email.text.toString().trim()
            card.website = web.text.toString().trim()
            card.address = address.text.toString().trim()
            card.note = note.text.toString().trim()
            if (card.company.isBlank() && card.name.isBlank()) {
                toast("Firma veya kişi adı gir.")
            } else {
                if (isNew) CardStore.add(this, card) else CardStore.update(this, card)
                dlg.dismiss()
                refresh()
            }
        }
    }

    // ------------------------------------------------------------------ sesle yazma

    private fun dictate(target: EditText?) {
        dictateTarget = target
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Konuş…")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_DICTATE)
        } catch (e: Exception) {
            toast("Sesli giriş açılamadı. Telefonda Google uygulaması kurulu olmalı.")
        }
    }

    // ------------------------------------------------------------------ fotoğraf

    private fun capture() {
        val dir = File(cacheDir, "tmp")
        dir.mkdirs()
        val f = File(dir, "capture.jpg")
        if (f.exists()) f.delete()
        pendingCapture = f
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            i.clipData = ClipData.newRawUri("", uri)
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_CAPTURE)
        } catch (e: Exception) {
            toast("Kamera açılamadı.")
        }
    }

    private fun pickFromGallery() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            toast("Galeri açılamadı.")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_CAPTURE -> {
                val f = pendingCapture
                if (f != null && f.exists() && f.length() > 0) {
                    processPhoto { f }
                } else {
                    toast("Fotoğraf alınamadı.")
                }
            }
            REQ_PICK -> {
                val uri = data?.data
                if (uri != null) {
                    processPhoto {
                        val dir = File(cacheDir, "tmp")
                        dir.mkdirs()
                        val f = File(dir, "pick.jpg")
                        val input = contentResolver.openInputStream(uri)
                            ?: throw IOException("Fotoğraf açılamadı")
                        input.use { ins -> f.outputStream().use { ins.copyTo(it) } }
                        f
                    }
                }
            }
            REQ_DICTATE -> {
                val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    val t = dictateTarget
                    if (t == null) {
                        search.setText(text)
                    } else {
                        val cur = t.text.toString().trim()
                        t.setText(if (cur.isEmpty()) text else "$cur $text")
                        t.setSelection(t.text.length)
                    }
                }
            }
        }
    }

    private fun JSONObject.str(key: String): String {
        val v = opt(key)
        return if (v == null || v == JSONObject.NULL) "" else v.toString().trim()
    }

    /** Fotoğrafı küçültüp kaydeder, Gemini ile okur, düzeltme formunu açar. */
    private fun processPhoto(loader: () -> File) {
        val progress = AlertDialog.Builder(this)
            .setMessage("Kartvizit okunuyor…")
            .setCancelable(false)
            .show()

        worker.execute {
            var photoName = ""
            var data: JSONObject? = null
            var error = ""
            try {
                val src = loader()
                photoName = "c${System.currentTimeMillis()}.jpg"
                val bytes = prepareImage(src, CardStore.photoFile(this, photoName))
                try {
                    data = Agent.extractCard(this, bytes)
                } catch (e: Exception) {
                    error = e.message ?: "bilinmeyen hata"
                }
            } catch (e: Exception) {
                error = e.message ?: "fotoğraf işlenemedi"
            }

            runOnUiThread {
                progress.dismiss()
                if (isFinishing) return@runOnUiThread
                if (photoName.isBlank() || !CardStore.photoFile(this, photoName).exists()) {
                    toast("Fotoğraf işlenemedi: $error")
                    return@runOnUiThread
                }
                val card = Card(photo = photoName)
                val d = data
                if (d != null) {
                    card.company = d.str("company")
                    card.name = d.str("name")
                    card.title = d.str("title")
                    card.phone = d.str("phone")
                    card.email = d.str("email")
                    card.website = d.str("website")
                    card.address = d.str("address")
                } else {
                    toast("Otomatik okunamadı ($error). Bilgileri elle girebilirsin.")
                }
                showForm(card, true)
            }
        }
    }

    /** Döndürmeyi düzeltir, 1600 px'e küçültür, JPEG olarak dest'e yazar ve baytları döndürür. */
    private fun prepareImage(src: File, dest: File): ByteArray {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(src.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        var bmp = BitmapFactory.decodeFile(src.path, opts)
            ?: throw IOException("Fotoğraf okunamadı")

        val rotation = try {
            val exif = ExifInterface(src.path)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (rotation != 0f) {
            val m = Matrix()
            m.postRotate(rotation)
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val side = max(bmp.width, bmp.height)
        if (side > 1600) {
            val s = 1600f / side
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val bytes = out.toByteArray()
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        return bytes
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val REQ_CAPTURE = 11
        private const val REQ_PICK = 12
        private const val REQ_DICTATE = 13
    }
}
