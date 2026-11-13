package com.example.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

class ListenService : Service(), TextToSpeech.OnInitListener {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private lateinit var cmds: Commands
    private var ttsReady = false
    @Volatile private var speaking = false
    @Volatile private var loading = false
    private var busy = false
    private var pendingListen = false
    private var gRec: SpeechRecognizer? = null
    private var gFails = 0
    private var gRetry = 0
    private var wFails = 0
    @Volatile private var wakeGen = 0
    private var engine = "Offline"
    @Volatile private var recording = false
    private var ttsFallback = false
    private var model: Model? = null
    private var wakeRec: Recognizer? = null
    private var cmdRec: Recognizer? = null
    private var speech: SpeechService? = null
    private val wake by lazy { getSharedPreferences("cfg", MODE_PRIVATE).getString("wake", "brother")!!.lowercase().trim() }

    private inner class Hear(val partial: (String) -> Unit = {}, val final: (String) -> Unit = {}, val timeout: () -> Unit = {}) :
        org.vosk.android.RecognitionListener {
        private fun txt(j: String?) = try { JSONObject(j ?: "").let { it.optString("text", it.optString("partial", "")) } } catch (e: Exception) { "" }
        override fun onPartialResult(h: String?) { partial(txt(h)) }
        override fun onResult(h: String?) { final(txt(h)) }
        override fun onFinalResult(h: String?) {}
        override fun onError(e: Exception?) { note("Mic error: ${e?.message}") }
        override fun onTimeout() { timeout() }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        installCrashLog(applicationContext)
        tts = TextToSpeech(this, this, "com.google.android.tts")
        cmds = Commands(this, { say(it) }, true)
    }

    override fun onInit(s: Int) {
        if (s != TextToSpeech.SUCCESS) { if (!ttsFallback) { ttsFallback = true; tts = TextToSpeech(this, this) }; return }
        tts.language = Locale.getDefault(); ttsReady = true
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { speaking = false; ui.post { if (cmds.awaiting()) { busy = true; onWake() } else startWake() } }
            override fun onDone(id: String?) { speaking = false; ui.post { if (cmds.awaiting()) { busy = true; onWake() } else startWake() } }
        })
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, build("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, build("Starting…"))
        } catch (e: Exception) { status("Couldn't start in the background: ${e.message}"); stopSelf(); return START_NOT_STICKY }
        val listenNow = i?.action == "LISTEN"
        if (model != null && cmdRec != null) {
            if (listenNow) ui.post { speech?.cancel(); busy = true; onWake() } else if (speech == null) ui.post { startWake() }
        } else {
            pendingListen = pendingListen || listenNow
            if (!loading) { loading = true; Thread { loadModel() }.start() }
        }
        return START_STICKY
    }

    private fun build(t: String): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("a", "Assistant", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "a").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("My Assistant: say “$wake”").setContentText(t).setOngoing(true).build()
    }
    private fun note(t: String) = getSystemService(NotificationManager::class.java).notify(1, build(t))

    private fun status(t: String) { note(t); ui.post { Bus.onLog?.invoke("ℹ️ $t") } }

    private fun copyAssets(path: String, dest: File) {
        val list = assets.list(path) ?: emptyArray()
        if (list.isEmpty()) {
            try { dest.parentFile?.mkdirs(); assets.open(path).use { i -> dest.outputStream().use { o -> i.copyTo(o) } } }
            catch (e: java.io.FileNotFoundException) { dest.mkdirs() }
        } else { dest.mkdirs(); for (n in list) copyAssets("$path/$n", File(dest, n)) }
    }

    private fun unzip(zip: File, dir: File) {
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val rel = e.name.substringAfter('/')
                if (rel.isNotEmpty()) {
                    val f = File(dir, rel)
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                }
                e = z.nextEntry
            }
        }
    }

    private fun download(u: String, dir: File) {
        val part = File(filesDir, "m" + Math.abs(u.hashCode()) + ".part")
        val have = if (part.exists()) part.length() else 0L
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 30000
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        val code = c.responseCode
        if (code != 416) {
            val append = have > 0 && code == 206
            var tot = if (append) have else 0L; var last = tot
            java.io.FileOutputStream(part, append).use { o ->
                c.inputStream.use { i ->
                    val b = ByteArray(65536); var n = i.read(b)
                    while (n > 0) {
                        o.write(b, 0, n); tot += n
                        if (tot - last > 2000000) { last = tot; note("Downloading voice model… ${tot / 1000000} MB (progress is saved)") }
                        n = i.read(b)
                    }
                }
            }
        }
        dir.deleteRecursively()
        try { unzip(part, dir) } catch (e: Exception) { part.delete(); dir.deleteRecursively(); throw e }
        part.delete()
    }

    private fun loadModel() {
        val dir = File(filesDir, "model-in")
        try {
            if (!File(dir, "done").exists()) {
                dir.deleteRecursively()
                if ((assets.list("model-in") ?: emptyArray()).isNotEmpty()) {
                    status("Preparing the offline voice model (one time only, no internet needed)…")
                    copyAssets("model-in", dir)
                } else {
                    var err = ""; var ok = false
                    for (u in listOf("https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip",
                                     "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip")) {
                        try { status("Downloading voice model (about 40 MB). Progress is saved if it stops."); download(u, dir); ok = true; break }
                        catch (th: Throwable) { err = th.toString() }
                    }
                    if (!ok) { loading = false; status("Download failed: $err. Connect to the internet, tap Stop, then Start again. Progress is saved."); return }
                }
                File(dir, "done").writeText("ok")
            }
            status("Loading voice model…")
            val m = try { Model(dir.absolutePath) } catch (th: Throwable) {
                val sp = getSharedPreferences("cfg", MODE_PRIVATE)
                if (!sp.getBoolean("badModel", false)) { sp.edit().putBoolean("badModel", true).apply(); dir.deleteRecursively() }
                throw th
            }
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("badModel", false).apply()
            model = m
            wakeRec = Recognizer(m, 16000f, "[\"$wake\", \"hey $wake\", \"ok $wake\", \"[unk]\"]")
            cmdRec = Recognizer(m, 16000f)
            status("Ready. Say “$wake”, or tap Tap to talk. Speech: " +
                (if (groqKey() != null) "cloud speech (best)" else "Google/offline (add a Groq key in Settings for better accuracy)"))
            ui.post { if (pendingListen) { pendingListen = false; busy = true; onWake() } else startWake() }
        } catch (th: Throwable) { loading = false; status("Voice model error: $th") }
    }

    private fun listenWith(r: Recognizer, timeout: Int?, l: Hear) {
        try {
            speech?.shutdown(); r.reset()
            speech = SpeechService(r, 16000f).also { if (timeout == null) it.startListening(l) else it.startListening(l, timeout) }
        } catch (e: Exception) { note("Mic error: ${e.message}") }
    }

    private fun startWake() {
        val r = wakeRec ?: return
        if (speaking) return
        cmds.pending = null
        val gen = ++wakeGen
        busy = false; note("Listening for “$wake”")
        Thread {
            var rec: AudioRecord? = null
            try {
                Thread.sleep(150)
                if (gen != wakeGen) return@Thread
                val chunk = 1600
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 8))
                if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Microphone is busy or blocked")
                val buf = ShortArray(chunk); var peak = 2000.0; var raw = 0; var loops = 0
                r.reset(); rec.startRecording()
                while (gen == wakeGen) {
                    val n = rec.read(buf, 0, chunk)
                    if (n <= 0) { Thread.sleep(20); continue }
                    var cp = 0; for (i in 0 until n) cp = maxOf(cp, Math.abs(buf[i].toInt()))
                    raw = maxOf(raw, cp); peak = maxOf(cp.toDouble(), peak * 0.995)
                    val g = (12000.0 / peak).coerceIn(1.0, 6.0)
                    if (g > 1.05) for (i in 0 until n) buf[i] = (buf[i] * g).toInt().coerceIn(-32768, 32767).toShort()
                    if (++loops == 40) {
                        val lv = raw
                        ui.post { status(if (lv < 150) "Mic is almost silent (level $lv). Check Microphone permission or close other apps using the mic." else "Microphone OK (level $lv). Say “$wake”.") }
                    }
                    val end = r.acceptWaveForm(buf, n)
                    val js = JSONObject(if (end) r.result else r.partialResult)
                    val txt = js.optString("text", js.optString("partial", ""))
                    if (txt.split(" ").contains(wake)) { wakeGen++; ui.post { if (!busy) { busy = true; onWake() } } }
                }
            } catch (e: Throwable) { ui.post { status("Mic error: ${e.message}") } }
            finally { try { rec?.stop() } catch (e2: Exception) {}; rec?.release() }
        }.start()
    }

    private fun runCommand(t: String) {
        wakeGen++; speech?.cancel(); note("Heard: $t"); Bus.onLog?.invoke("🧑 [$engine] $t")
        cmds.lowConf = (engine == "Offline"); cmds.handle(t)
        if (!speaking) { if (cmds.awaiting()) { busy = true; onWake() } else startWake() }
    }

    private fun googleOn() = gFails < 2 && cmds.online() && SpeechRecognizer.isRecognitionAvailable(this) &&
        getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("google", true)

    private fun onWake() {
        wakeGen++; speech?.cancel(); status("Wake word heard. Speak now…")
        try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 150) } catch (e: Exception) {}
        ui.postDelayed({ if (whisperOn()) listenWhisper() else if (googleOn()) listenGoogle() else listenVosk() }, 450)
    }

    private fun listenVosk() {
        engine = "Offline"
        val r = cmdRec ?: return
        var done = false
        listenWith(r, 7000, Hear(
            final = { t ->
                if (!done) {
                    done = true; speech?.cancel()
                    if (t.isBlank()) startWake() else runCommand(t)
                }
            },
            timeout = { if (!done) { done = true; startWake() } }))
    }

    private fun langPref() = getSharedPreferences("cfg", MODE_PRIVATE).getString("lang", "hi") ?: "hi"
    private fun groqKey() = (getSharedPreferences("cfg", MODE_PRIVATE).getString("key", "") ?: "").takeIf { it.startsWith("gsk_") || it.startsWith("xai-") }
    private fun whisperOn() = groqKey() != null && wFails < 3 && cmds.online()

    private fun listenWhisper() {
        speech?.shutdown(); speech = null
        val key = groqKey() ?: return
        if (recording) return
        recording = true; engine = if (key.startsWith("xai-")) "xAI" else "Whisper"
        val lang = when (langPref()) { "hi" -> "hi"; "en" -> "en"; else -> "" }
        val hint = cmds.whisperHint()
        note("Listening… speak now")
        Thread {
            var err = ""
            val text = try { transcribe(record(), key, lang, hint) } catch (e: Throwable) { err = e.message ?: e.toString(); null }
            ui.post {
                recording = false
                when {
                    text == null -> { wFails++; status("Cloud speech failed: $err. Using backup voice."); if (googleOn()) listenGoogle() else listenVosk() }
                    text.trim().length < 2 -> { status("I didn't hear anything. Speak right after the beep, closer to the mic."); startWake() }
                    else -> { wFails = 0; runCommand(text.lowercase().trim()) }
                }
            }
        }.start()
    }

    private fun record(): ByteArray {
        val rate = 16000; val chunk = 1600
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 8))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("Microphone is busy or blocked") }
        val agc = try { if (android.media.audiofx.AutomaticGainControl.isAvailable()) android.media.audiofx.AutomaticGainControl.create(rec.audioSessionId)?.also { it.enabled = true } else null } catch (e: Exception) { null }
        val ns = try { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true } else null } catch (e: Exception) { null }
        val out = java.io.ByteArrayOutputStream(); val buf = ShortArray(chunk)
        var low = 1e9; var heard = false; var quiet = 0; var chunks = 0
        try {
            rec.startRecording()
            while (chunks < 100) {
                val r = rec.read(buf, 0, chunk); if (r <= 0) break
                var s = 0.0; for (i in 0 until r) s += buf[i].toDouble() * buf[i]
                val rms = Math.sqrt(s / r)
                for (i in 0 until r) { out.write(buf[i].toInt() and 0xff); out.write((buf[i].toInt() shr 8) and 0xff) }
                chunks++
                if (chunks <= 3) { low = minOf(low, rms); if (rms > 1200) heard = true; continue }
                val th = (low * 3).coerceIn(200.0, 1000.0)
                if (rms > th) { heard = true; quiet = 0 } else if (heard) { quiet++; if (quiet >= 12) break }
                if (!heard && chunks > 50) break
            }
        } finally { try { rec.stop() } catch (e: Exception) {}; agc?.release(); ns?.release(); rec.release() }
        if (!heard) return ByteArray(0)
        val b = out.toByteArray(); var peak = 1
        for (i in 0 until b.size - 1 step 2) peak = maxOf(peak, Math.abs((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)))
        val g = minOf(8.0, 20000.0 / peak)
        if (g > 1.2) for (i in 0 until b.size - 1 step 2) {
            val v = (((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)) * g).toInt().coerceIn(-32768, 32767)
            b[i] = (v and 0xff).toByte(); b[i + 1] = (v shr 8).toByte()
        }
        return b
    }

    private fun transcribe(pcm: ByteArray, key: String, lang: String, hint: String): String? {
        if (pcm.size < 16000) return ""
        val wav = java.io.ByteArrayOutputStream()
        fun le(v: Int, n: Int) { for (i in 0 until n) wav.write((v shr (8 * i)) and 0xff) }
        wav.write("RIFF".toByteArray()); le(36 + pcm.size, 4); wav.write("WAVEfmt ".toByteArray()); le(16, 4); le(1, 2); le(1, 2)
        le(16000, 4); le(32000, 4); le(2, 2); le(16, 2); wav.write("data".toByteArray()); le(pcm.size, 4); wav.write(pcm)
        val b = "----brother" + System.currentTimeMillis()
        val c = URL(if (key.startsWith("xai-")) "https://api.x.ai/v1/stt" else "https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 20000
        c.setRequestProperty("Authorization", "Bearer $key"); c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
        c.outputStream.use { o ->
            fun f(n: String, v: String) = o.write("--$b\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray())
            if (key.startsWith("xai-")) { f("keyterm", "Brother"); if (lang.isNotEmpty()) { f("format", "true"); f("language", lang) } }
            else {
                f("model", WHISPER_MODEL); f("temperature", "0")
                if (lang.isNotEmpty()) f("language", lang)
                f("prompt", hint)
            }
            o.write("--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
            o.write(wav.toByteArray()); o.write("\r\n--$b--\r\n".toByteArray())
        }
        if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode} " + (c.errorStream?.bufferedReader()?.readText()?.take(120) ?: ""))
        return JSONObject(c.inputStream.bufferedReader().readText()).optString("text", "")
    }

    private fun listenGoogle() {
        engine = "Google"
        speech?.shutdown(); speech = null
        gRec?.destroy()
        val hi = langPref() == "hi"
        val r = SpeechRecognizer.createSpeechRecognizer(this); gRec = r
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) startWake() else { gFails = 0; gRetry = 0; runCommand(t.lowercase()) }
            }
            override fun onError(e: Int) {
                if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { gRetry = 0; status("I didn't hear anything. Speak right after the beep."); startWake(); return }
                if (gRetry < 1) { gRetry++; ui.postDelayed({ listenGoogle() }, 400) }
                else { gRetry = 0; gFails++; status("Google speech error $e. Using offline voice (less accurate)."); listenVosk() }
            }
            override fun onReadyForSpeech(p: Bundle?) { note("Listening… speak now") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (hi) "hi-IN" else "en-IN")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        })
    }

    private fun say(t: String) {
        Bus.onLog?.invoke("🤖 $t")
        if (!ttsReady) return
        speaking = true; wakeGen++; speech?.cancel()
        prepVoice(tts, this, t)
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "a")
    }

    override fun onDestroy() {
        wakeGen++; try { Thread.sleep(250) } catch (e: Exception) {}
        speech?.shutdown(); gRec?.destroy(); wakeRec?.close(); cmdRec?.close(); model?.close(); tts.shutdown()
        super.onDestroy()
    }
}

private const val WHISPER_MODEL = "whisper-large-v3-turbo"
