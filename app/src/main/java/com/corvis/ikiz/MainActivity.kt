package com.corvis.ikiz

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity(), Host {

    private val bg = Color.parseColor("#0B1220")
    private val botColor = Color.parseColor("#1F2937")
    private val userColor = Color.parseColor("#4F46E5")
    private val micIdle = Color.parseColor("#4F46E5")
    private val micActive = Color.parseColor("#DC2626")

    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var mic: ImageButton

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var busy = false
    private var handsFree = false

    private val history = mutableListOf<Pair<String, String>>()
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()
        initTts()
        requestPerms()
        addBubble(
            "Merhaba! Mikrofona dokunup konuşabilirsin. Örneğin: \"Yarın saat on bire Ahmet'le toplantı ekle\" ya da \"Bugün neler var?\"",
            false
        )
        setStatus("Mikrofona dokun ve konuş")
        if (Store.apiKey(this).isBlank()) showSettings()
    }

    override fun onStop() {
        super.onStop()
        recognizer?.cancel()
        setListening(false)
        tts?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        worker.shutdown()
    }

    // ------------------------------------------------------------------ arayüz

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.setPadding(dp(14), dp(10), dp(14), dp(10))

        // Başlık satırı
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "Dijital İkiz"
        title.textSize = 22f
        title.setTextColor(Color.WHITE)
        title.setTypeface(title.typeface, Typeface.BOLD)
        header.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        val cardsBtn = TextView(this)
        cardsBtn.text = "Kartvizit"
        cardsBtn.textSize = 14f
        cardsBtn.setTextColor(Color.WHITE)
        val chip = GradientDrawable()
        chip.cornerRadius = dp(14).toFloat()
        chip.setColor(userColor)
        cardsBtn.background = chip
        cardsBtn.setPadding(dp(12), dp(6), dp(12), dp(6))
        cardsBtn.setOnClickListener { startActivity(Intent(this, CardsActivity::class.java)) }
        header.addView(cardsBtn, LinearLayout.LayoutParams(WRAP, WRAP))
        val settingsBtn = TextView(this)
        settingsBtn.text = "⚙"
        settingsBtn.textSize = 26f
        settingsBtn.setTextColor(Color.WHITE)
        settingsBtn.setPadding(dp(12), dp(4), dp(4), dp(4))
        settingsBtn.setOnClickListener { showSettings() }
        header.addView(settingsBtn, LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        // Sohbet alanı
        scroll = ScrollView(this)
        chat = LinearLayout(this)
        chat.orientation = LinearLayout.VERTICAL
        scroll.addView(chat, ViewGroup.LayoutParams(MATCH, WRAP))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Durum satırı
        status = TextView(this)
        status.textSize = 13f
        status.setTextColor(Color.parseColor("#9CA3AF"))
        status.gravity = Gravity.CENTER
        status.setPadding(0, dp(6), 0, dp(6))
        root.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))

        // Yazı girişi
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        input = EditText(this)
        input.hint = "Yaz veya mikrofona dokun…"
        input.setHintTextColor(Color.parseColor("#6B7280"))
        input.setTextColor(Color.WHITE)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit(input.text.toString())
                true
            } else {
                false
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))
        val send = Button(this)
        send.text = "Gönder"
        send.setOnClickListener { submit(input.text.toString()) }
        row.addView(send, LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))

        // Mikrofon düğmesi
        mic = ImageButton(this)
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(micIdle)
        mic.background = circle
        mic.setImageResource(R.drawable.ic_mic)
        mic.scaleType = ImageView.ScaleType.FIT_CENTER
        mic.setPadding(dp(20), dp(20), dp(20), dp(20))
        mic.contentDescription = "Konuş"
        mic.setOnClickListener { onMicClick() }
        val micParams = LinearLayout.LayoutParams(dp(76), dp(76))
        micParams.gravity = Gravity.CENTER_HORIZONTAL
        micParams.topMargin = dp(10)
        root.addView(mic, micParams)

        // Sürekli sohbet
        val sw = Switch(this)
        sw.text = "Sürekli sohbet: yanıttan sonra tekrar dinle"
        sw.setTextColor(Color.parseColor("#9CA3AF"))
        sw.setOnCheckedChangeListener { _, checked -> handsFree = checked }
        val swParams = LinearLayout.LayoutParams(MATCH, WRAP)
        swParams.topMargin = dp(8)
        root.addView(sw, swParams)

        setContentView(root)
    }

    private fun addBubble(msg: String, fromUser: Boolean): TextView {
        val tv = TextView(this)
        tv.text = msg
        tv.textSize = 16f
        tv.setTextColor(if (fromUser) Color.WHITE else Color.parseColor("#E5E7EB"))
        tv.setPadding(dp(14), dp(10), dp(14), dp(10))
        tv.setTextIsSelectable(true)
        val shape = GradientDrawable()
        shape.cornerRadius = dp(16).toFloat()
        shape.setColor(if (fromUser) userColor else botColor)
        tv.background = shape
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.topMargin = dp(6)
        lp.gravity = if (fromUser) Gravity.END else Gravity.START
        if (fromUser) lp.leftMargin = dp(48) else lp.rightMargin = dp(48)
        chat.addView(tv, lp)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        return tv
    }

    private fun setStatus(s: String) {
        status.text = s
    }

    private fun setListening(on: Boolean) {
        listening = on
        val d = mic.background
        if (d is GradientDrawable) d.setColor(if (on) micActive else micIdle)
    }

    // ------------------------------------------------------------------ izinler

    private fun requestPerms() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    // ------------------------------------------------------------------ ses girişi

    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            setListening(false)
            val msg = errorText(error)
            if (msg.isNotEmpty()) setStatus(msg)
        }

        override fun onResults(results: Bundle?) {
            setListening(false)
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) submit(text) else setStatus("Seni anlayamadım, tekrar dene.")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!t.isNullOrBlank()) setStatus(t)
        }
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ses duyulmadı. Mikrofona tekrar dokun."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Konuşma tanıma için internet gerekebilir."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon izni verilmemiş."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Tanıyıcı meşgul, bir saniye sonra tekrar dene."
        SpeechRecognizer.ERROR_CLIENT -> ""
        else -> "Ses tanıma hatası ($code)"
    }

    private fun onMicClick() {
        if (listening) {
            recognizer?.stopListening()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Mikrofon izni gerekiyor.")
            requestPerms()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Bu telefonda konuşma tanıma servisi yok. Google uygulamasını kurup güncelle.")
            return
        }
        if (busy) {
            setStatus("Önceki isteği bitiriyorum…")
            return
        }
        stopTts()
        if (recognizer == null) {
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            r.setRecognitionListener(recListener)
            recognizer = r
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        setListening(true)
        setStatus("Dinliyorum…")
        recognizer?.startListening(i)
    }

    // ------------------------------------------------------------------ ses çıkışı

    private fun initTts() {
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("tr", "TR"))
                ttsReady = r != null &&
                    r != TextToSpeech.LANG_MISSING_DATA &&
                    r != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread { afterSpeech() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread { afterSpeech() }
                    }
                })
                if (!ttsReady) {
                    runOnUiThread {
                        setStatus("Türkçe ses verisi yok: Ayarlar > Metin okuma bölümünden Türkçe sesi indir.")
                    }
                }
            }
        }
    }

    private fun speak(t: String) {
        val clean = t.replace(Regex("[*#`_>]"), "").trim()
        if (!ttsReady || clean.isEmpty()) {
            afterSpeech()
            return
        }
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "ikiz")
    }

    private fun stopTts() {
        tts?.stop()
    }

    private fun afterSpeech() {
        if (!handsFree) return
        mic.postDelayed({
            if (handsFree && !busy && !listening) startListening()
        }, 500)
    }

    // ------------------------------------------------------------------ istek gönderme

    private fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (busy) {
            setStatus("Önceki isteği bitiriyorum…")
            return
        }
        busy = true
        stopTts()
        addBubble(t, true)
        input.setText("")
        val thinking = addBubble("…", false)
        setStatus("Düşünüyorum…")

        worker.execute {
            val reply = try {
                Agent.run(this, this, t, history)
            } catch (e: UnknownHostException) {
                "İnternet bağlantısı yok gibi görünüyor."
            } catch (e: SocketTimeoutException) {
                "Sunucu zamanında yanıt vermedi, tekrar dener misin?"
            } catch (e: Exception) {
                "Bir hata oldu: ${e.message}"
            }
            runOnUiThread {
                thinking.text = reply
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                busy = false
                setStatus("Mikrofona dokun ve konuş")
                speak(reply)
            }
        }
    }

    // ------------------------------------------------------------------ onay (Host)

    override fun confirm(message: String): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Onay")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Onayla") { _, _ ->
                    result.set(true)
                    latch.countDown()
                }
                .setNegativeButton("İptal") { _, _ -> latch.countDown() }
                .show()
        }
        latch.await(120, TimeUnit.SECONDS)
        return result.get()
    }

    // ------------------------------------------------------------------ ayarlar

    private fun showSettings() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)

        fun label(s: String) {
            val tv = TextView(this)
            tv.text = s
            tv.textSize = 13f
            tv.setPadding(0, dp(10), 0, 0)
            box.addView(tv)
        }

        label("Gemini API anahtarı (aistudio.google.com). Yalnızca bu telefonda saklanır.")
        val keyField = EditText(this)
        keyField.setText(Store.apiKey(this))
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        box.addView(keyField)

        label("Model adı")
        val modelField = EditText(this)
        modelField.setText(Store.model(this))
        modelField.setSingleLine(true)
        box.addView(modelField)

        label("Senin hakkında kısa bilgi (adın, işin, tercihlerin)")
        val infoField = EditText(this)
        infoField.setText(Store.userInfo(this))
        infoField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        box.addView(infoField)

        AlertDialog.Builder(this)
            .setTitle("Ayarlar")
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                Store.saveSettings(
                    this,
                    keyField.text.toString(),
                    modelField.text.toString(),
                    infoField.text.toString()
                )
                setStatus("Ayarlar kaydedildi.")
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
