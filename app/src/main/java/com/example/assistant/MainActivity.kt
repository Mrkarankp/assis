package com.example.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.widget.CheckBox
import android.widget.EditText
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private lateinit var log: ChatView
    private lateinit var statusTv: TextView
    private lateinit var scroll: ScrollView
    private lateinit var talk: Button
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var forceStandard = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        installCrashLog(applicationContext)
        tts = TextToSpeech(this, this, "com.google.android.tts")
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(36), dp(16), dp(12)); setBackgroundColor(0xFF0E1116.toInt()) }
        val title = TextView(this).apply { text = "Brother"; textSize = 26f; setTextColor(0xFFFFFFFF.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        statusTv = TextView(this).apply { text = "Say “Brother” or tap the mic"; textSize = 14f; setTextColor(0xFF8B99A8.toInt()) }
        root.addView(title); root.addView(statusTv)
        scroll = ScrollView(this)
        log = ChatView(this, scroll)
        scroll.addView(log.box)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        talk = Button(this).apply { text = "🎤"; textSize = 34f; setTextColor(0xFFFFFFFF.toInt()); setOnClickListener { talkViaService() } }
        setOrb(0xFF5B3DF5.toInt())
        root.addView(talk, LinearLayout.LayoutParams(dp(92), dp(92)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8); bottomMargin = dp(8) })
        fun flat(label: String, f: (Button) -> Unit) = Button(this).apply {
            text = label; textSize = 12f; transformationMethod = null; setTextColor(0xFFE8EDF2.toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1B2230.toInt()); cornerRadius = dp(14).toFloat() }
            setOnClickListener { f(this) }
        }
        val bg = flat("▶ Start listening") { toggleService(it) }
        bgBtn = bg
        val st1 = flat("⚙ Settings") { settingsDialog() }
        val pb = flat("🔐 Permissions") { permDialog() }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (b2 in listOf(bg, st1, pb)) btnRow.addView(b2, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        root.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        val typed = EditText(this).apply { hint = "Type a command to test…"; setTextColor(0xFFE8EDF2.toInt()); setHintTextColor(0xFF6B7788.toInt()); setSingleLine() }
        val go = flat("Run") { val v = typed.text.toString().trim(); if (v.isNotEmpty()) { log.append("⌨️ $v"); typedCmds.handle(v); typed.setText("") } }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(typed, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(go, LinearLayout.LayoutParams(dp(64), dp(44)))
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        setContentView(root)
        Bus.onState = { st2 -> applyState(st2) }
        Bus.onLog = { s -> log.append(s + "\n\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
        val cf = java.io.File(filesDir, "crash.txt")
        if (cf.exists()) { log.append("⚠️ The app closed last time because of this error (send me a screenshot):\n" + cf.readText().take(700) + "\n\n"); cf.delete() }
        if ((getSharedPreferences("cfg", MODE_PRIVATE).getString("key", "") ?: "").isBlank()) log.append("⚠️ AI key set nahi hai. Settings me Groq (gsk_…) ya xAI (xai-…) key paste karke Save karo. Bina key ke awaaz galat samjhi jayegi.\n\n")
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), 3)
        log.append("🤖 Hi, I'm Brother. Tap the mic, or tap Start listening and say “Brother”. Say help to hear what I can do.")
        if (intent?.action == Intent.ACTION_ASSIST) { talkViaService() }
    }

    private var ttsFallback = false
    override fun onInit(s: Int) { if (s != TextToSpeech.SUCCESS && !ttsFallback) { ttsFallback = true; tts = TextToSpeech(this, this) } }

    private fun say(t: String) {
        log.append("🤖 $t\n\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        prepVoice(tts, this, t)
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "a")
    }

    private fun resetButton() { listening = false; talk.text = "🎤  Tap to talk" }

    private fun listen() {
        if (listening) { recognizer?.stopListening(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        recognizer?.destroy()
        recognizer = if (!forceStandard && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
        listening = true
        talk.text = "Starting…"
        recognizer!!.setRecognitionListener(object : RecognitionListener {
            override fun onResults(r: Bundle?) {
                resetButton()
                val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) say("I didn't hear anything.") else { log.append("🧑 $t\n"); Commands(this@MainActivity, { say(it) }, false).handle(t) }
            }
            override fun onError(e: Int) {
                resetButton()
                if (e == SpeechRecognizer.ERROR_CLIENT) return
                if ((e == 11 || e == 12 || e == 13) && !forceStandard) { forceStandard = true; talk.post { listen() }; return }
                val why = when (e) {
                    6, 7 -> "I didn't hear a command. Tap, wait for “Listening”, then speak."
                    9 -> "Microphone permission is missing."
                    11 -> "The phone's speech service keeps disconnecting. Use always-listening mode instead; it doesn't need it."
                    12, 13 -> "This language isn't available offline. Download it in Settings > Voice input."
                    1, 2, 3, 4, 8 -> "Speech service problem. Check the offline language pack or internet."
                    else -> "Speech problem."
                }
                say("$why (error $e)")
            }
            override fun onReadyForSpeech(p: Bundle?) { talk.text = "Listening… (tap to stop)" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { talk.text = "Thinking…" }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !hindi())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (hindi()) "hi-IN" else Locale.getDefault().toLanguageTag())
        })
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, g: IntArray) {
        if (c == 3) return
        if (c == 2) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) bgBtn?.let { beginService(it) }
            else say("I need the microphone. Open Settings > Apps > My Assistant > Permissions and allow Microphone.")
            return
        }
        if (g.firstOrNull() == PackageManager.PERMISSION_GRANTED) { talkViaService() } else say("I need the microphone permission.")
    }

    private fun hindi() = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("hi", false)

    private fun setOrb(c: Int) {
        val dd = resources.displayMetrics.density
        talk.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(c); setStroke((6 * dd).toInt(), (c and 0x00FFFFFF) or 0x55000000)
        }
    }

    private fun applyState(s: String) {
        val wk = getSharedPreferences("cfg", MODE_PRIVATE).getString("wake", "brother") ?: "brother"
        when (s) {
            "listening" -> { setOrb(0xFF19C3FF.toInt()); statusTv.text = "Listening…" }
            "thinking" -> { setOrb(0xFFFFB020.toInt()); statusTv.text = "Thinking…" }
            "speaking" -> { setOrb(0xFF2ECC71.toInt()); statusTv.text = "Speaking…" }
            else -> { setOrb(0xFF5B3DF5.toInt()); statusTv.text = "Say “$wk” or tap the mic" }
        }
    }

    private fun permDialog() {
        val ov = Settings.canDrawOverlays(this); val ac = SendAccessibility.enabled(this)
        AlertDialog.Builder(this).setTitle("Phone permissions")
            .setItems(arrayOf((if (ov) "✅" else "⬜") + " Open apps hands-free (Display over other apps)",
                (if (ac) "✅" else "⬜") + " Auto-send WhatsApp messages (Accessibility)",
                "App info (Contacts, Phone, Microphone, Allow restricted settings)")) { _, w ->
                when (w) {
                    0 -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    1 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    else -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun settingsDialog() {
        val sp = getSharedPreferences("cfg", MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        fun field(h: String, v: String?, multi: Boolean = false) = EditText(this).apply { hint = h; setText(v); if (multi) { minLines = 3; inputType = 131073 } }
        val key = field("AI key: Groq (gsk_…), xAI Grok (xai-…) or Claude", sp.getString("key", "")).apply { inputType = 129 }
        val wake = field("Wake word (default: brother)", sp.getString("wake", "brother"))
        val mem = field("Saved memories and preferences (edit or delete)", sp.getString("mem", ""), true)
        val names = try { tts.voices.filter { it.locale.country == "IN" }.map { it.name }.sorted() } catch (e: Exception) { emptyList() }
        val spin = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Default voice") + names)
            setSelection(maxOf(0, names.indexOf(sp.getString("voice", "")) + 1))
        }
        val deep = CheckBox(this).apply { text = "Deeper, male-like voice"; isChecked = sp.getBoolean("deep", true) }
        val langs = listOf("hi", "en", "auto")
        val hi = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Speech: Hindi / Hinglish (best for Indian speech)", "Speech: English", "Speech: Auto-detect"))
            setSelection(maxOf(0, langs.indexOf(sp.getString("lang", "hi"))))
        }
        val conf = CheckBox(this).apply { text = "Ask me (yes/no) before sending or calling"; isChecked = sp.getBoolean("confirm", true) }
        val follow = CheckBox(this).apply { text = "Follow-up mode (keep listening after my answers)"; isChecked = sp.getBoolean("follow", true) }
        val city = field("Home city for weather", sp.getString("city", "Delhi"))
        val auto = CheckBox(this).apply { text = "Auto-send WhatsApp messages (needs Accessibility)"; isChecked = sp.getBoolean("autosend", true) }
        val goog = CheckBox(this).apply { text = "Use Google recognition when online (more accurate)"; isChecked = sp.getBoolean("google", true) }
        listOf(key, wake, city, mem, spin, deep, hi, conf, follow, goog, auto).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle("Settings").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                sp.edit().putString("key", key.text.toString().trim())
                    .putString("wake", wake.text.toString().trim().lowercase().ifEmpty { "brother" })
                    .putString("mem", mem.text.toString().trim().let { if (it.isEmpty()) "" else it + "\n" })
                    .putString("voice", if (spin.selectedItemPosition > 0) names[spin.selectedItemPosition - 1] else "")
                    .putBoolean("deep", deep.isChecked).putString("lang", langs[hi.selectedItemPosition]).putBoolean("confirm", conf.isChecked).putBoolean("google", goog.isChecked).putBoolean("autosend", auto.isChecked).putBoolean("follow", follow.isChecked).putString("city", city.text.toString().trim().ifEmpty { "Delhi" }).apply()
                say("Saved. Restart always-listening to apply a new wake word.")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun talkViaService() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return }
        try { startForegroundService(Intent(this, ListenService::class.java).setAction("LISTEN")) } catch (e: Exception) { say("Couldn't start: ${e.message}"); return }
        svcOn = true; bgBtn?.text = "■  Stop always-listening"
    }

    private val typedCmds by lazy { Commands(this, { say(it) }, false) }
    private var svcOn = false
    private var bgBtn: Button? = null
    private fun toggleService(btn: Button) {
        if (svcOn) { stopService(Intent(this, ListenService::class.java)); svcOn = false; btn.text = "▶  Start always-listening"; return }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) { need += Manifest.permission.READ_CONTACTS; need += Manifest.permission.CALL_PHONE }
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 2); return }
        beginService(btn)
    }

    private fun beginService(btn: Button) {
        try { startForegroundService(Intent(this, ListenService::class.java)) } catch (e: Exception) { say("Couldn't start: ${e.message}"); return }
        svcOn = true; btn.text = "■  Stop always-listening"
        say("Always-listening is on. The first time, I download a 40 MB voice model, so stay online. Then say Brother, followed by your command.")
        if (!Settings.canDrawOverlays(this)) log.append("ℹ️ Optional: to open apps while you're away, allow Display over other apps in this app's settings.\n\n")
    }

    override fun onDestroy() { Bus.onLog = null; Bus.onState = null; recognizer?.destroy(); tts.shutdown(); super.onDestroy() }
}
