package com.example.assistant

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import java.text.DateFormat
import java.util.Date
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class Commands(private val ctx: Context, private val say: (String) -> Unit, private val background: Boolean) {
    var lowConf = false
    var pending: (() -> Unit)? = null
    fun awaiting() = pending != null
    fun handle(raw: String) {
        pending?.let { p ->
            pending = null
            val y = raw.lowercase()
            if (Regex("\\b(yes|yeah|yep|yup|haan|han|haa|ha|ok|okay|sure|send|correct|kar do|bhej do)\\b").containsMatchIn(y) || y.contains("हाँ") || y.contains("हां") || y.contains("भेज")) p()
            else say("Okay, cancelled.")
            return
        }
        val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (lowConf || (sp.getString("key", "") ?: "").isBlank() || !online()) { local(raw); return }
        val msgs = JSONArray()
        Chat.hist.takeLast(10).forEach { msgs.put(JSONObject().put("role", it.first).put("content", it.second)) }
        msgs.put(JSONObject().put("role", "user").put("content", raw))
        callClaude(ROUTER + (sp.getString("mem", "") ?: ""), msgs) { r ->
            val a = r.trim()
            if (a.startsWith("CMD:")) local(a.removePrefix("CMD:").trim())
            else { Chat.hist.add("user" to raw); Chat.hist.add("assistant" to a); say(a) }
        }
    }

    private fun local(raw: String) {
        try { localImpl(raw) } catch (e: Exception) { say("Something went wrong: ${e.message}") }
    }

    private fun localImpl(raw: String) {
        val t = lead(norm(raw.lowercase().replace(Regex("[?.!,]"), "").trim()))
        val words = t.split(" ")
        val num = Regex("\\d+").find(t)?.value?.toIntOrNull()
        fun has(vararg k: String) = k.any { t.contains(it) }
        val verb = Regex("^(?:(?:please|can you|could you|brother|hey|ok)\\s+)*(?:open|launch|start)\\s+(.+)").find(t)
        val call = Regex("^(?:(?:please|can you|could you|brother|hey|ok)\\s+)*(?:call|dial)\\b(.*)").find(t)
        val note = Regex("^(note)\\s+(.+)").find(t)
        val mem = Regex("^(?:remember|always|from now on)\\s+.+").find(t)
        val mail = Regex("\\b(?:write|draft|compose)\\s+(?:an?\\s+)?e-?mail\\b(.*)").find(t)
        val pm = parseMsg(t)
        val wp = webParse(raw)
        val nav = Regex("\\b(?:navigate to|directions to|take me to|navigate)\\s+(.+)").find(t)
        val calc = Regex("(-?\\d+(?:\\.\\d+)?)\\s*(plus|\\+|minus|-|times|multiplied by|x|\\*|divided by|over|/)\\s*(-?\\d+(?:\\.\\d+)?)").find(t)
        val play = Regex("^play\\s+(.+)").find(t)
        val q = Regex("^(?:what is|what's|what are|who is|who's|who was|why|how does|how do|how to|explain|tell me about|define|meaning of|where is|when did|when was)\\b").containsMatchIn(t) &&
            !Regex("^(?:what(?:'s| is) (?:the |my |today's )?(?:time|date|day|battery(?: level| percentage)?|weather)(?: is it| right now| today| now| like| outside)?$|what time is it|what day is it|what(?:'s| is) your name|who are you|how are you)").containsMatchIn(t) && calc == null && !t.contains("weather")

        when {
            has("forget everything") || has("clear memory") -> { ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().remove("mem").apply(); Chat.hist.clear(); say("All memories cleared.") }
            has("what do you remember") || has("my memories") -> say(ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("mem", "")?.ifBlank { null } ?: "I don't have any saved memories.")
            mem != null -> { val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE); sp.edit().putString("mem", (sp.getString("mem", "") ?: "") + t + "\n").apply(); say("Okay, I'll remember that.") }
            mail != null -> emailDraft(mail.groupValues[1].trim().ifEmpty { "a short professional email" })
            q -> askClaude(t)
            has("timer") -> {
                val unit = if (has("hour")) 3600 else if (has("second")) 1 else 60
                val n = num ?: if (has("an hour")) 1 else null
                if (n == null) say("For how long? Say, set a timer for 5 minutes.") else {
                    if (background) Handler(Looper.getMainLooper()).postDelayed({ say("Your timer is done.") }, n * unit * 1000L)
                    else start(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, n * unit).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                    say("Timer set.")
                }
            }
            has("alarm") || has("wake me") -> {
                val m = Regex("(\\d{1,2})(?:[:\\s]+(\\d{2}))?\\s*([ap])m?\\b").find(t.replace(".", ""))
                    ?: Regex("(\\d{1,2})(?:[:\\s]+(\\d{2}))?()").find(t)
                if (m == null) say("What time? Say, set an alarm for 7:30 am.") else {
                    var h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                    if (m.groupValues[3] == "p" && h < 12) h += 12
                    if (m.groupValues[3] == "a" && h == 12) h = 0
                    start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h)
                        .putExtra(AlarmClock.EXTRA_MINUTES, min).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                    say("Alarm set for $h:${"%02d".format(min)}.")
                }
            }
            Regex("\\btime\\b").containsMatchIn(t) ->
                say("It's " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()))
            (Regex("\\b(date|today)\\b").containsMatchIn(t) || has("what day")) && !has("weather") ->
                say("Today is " + DateFormat.getDateInstance(DateFormat.FULL).format(Date()))
            wp != null -> {
                Bus.onLog?.invoke("🔎 ${wp.intent} → ${wp.name} (${wp.num})" + (if (wp.intent == "call") "" else ", message: \"${wp.body}\""))
                val sms = Regex("\\bsms\\b|text message").containsMatchIn(raw.lowercase())
                val act: () -> Unit = {
                    recentAdd(wp.name)
                    if (wp.intent == "call") { dial(wp.num); say("Calling ${wp.name}.") } else sendMsg(wp.name, wp.num, wp.body, !sms)
                }
                if (ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("confirm", true)) {
                    pending = act
                    say((if (wp.intent == "call") "Call ${wp.name}" else "Message ${wp.name}: " + (if (wp.body.isEmpty()) "empty message" else wp.body)) + ". Say yes to confirm.")
                } else act()
            }
            call != null -> {
                val d = call.groupValues[1].filter { it.isDigit() }
                if (d.isNotEmpty()) { dial(d); say("Calling $d.") } else {
                    val nm = call.groupValues[1].replace(Regex("\\b(?:please|on whatsapp|on phone)\\b"), "").trim().removePrefix("to ").trim()
                    val c = findContact(nm)
                    if (nm.isEmpty()) say("Who should I call?")
                    else if (c == null) say("I couldn't find $nm in your contacts. Check that Contacts permission is allowed.")
                    else { val act = { dial(c.second); say("Calling ${c.first}.") }; if (c.third >= 90) act() else { pending = act; say("Did you mean ${c.first}? Say yes to call.") } }
                }
            }
            pm != null -> {
                var hit: Triple<String, String, Int>? = null; var body = ""
                for ((nm, bd) in pm.first) { val c = findContact(nm); if (c != null) { hit = c; body = bd; break } }
                if (hit == null) say("I couldn't find ${pm.first.first().first} in your contacts.")
                else {
                    val h = hit
                    val act = { sendMsg(h.first, h.second, body, pm.second) }
                    if (h.third >= 90) act() else { pending = act; say("Did you mean ${h.first}? Say yes to continue.") }
                }
            }
            nav != null -> { start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(nav.groupValues[1])))); say("Starting navigation.") }
            has("next song") || has("next track") -> { key(KeyEvent.KEYCODE_MEDIA_NEXT); say("Next.") }
            has("previous song") || has("previous track") -> { key(KeyEvent.KEYCODE_MEDIA_PREVIOUS); say("Previous.") }
            has("pause") || has("stop the music") || has("stop music") -> key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            has("play music") || has("resume music") -> key(KeyEvent.KEYCODE_MEDIA_PLAY)
            play != null -> { start(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, play.groupValues[1])); say("Playing ${play.groupValues[1]}.") }
            has("volume") || Regex("\\bmute\\b").containsMatchIn(t) || has("louder") || has("quieter") -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC); val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val v = (if (has("mute")) 0 else if (num != null) num * max / 100
                    else if (has("down") || has("lower") || has("quieter") || has("decrease")) cur - max / 5 else cur + max / 5).coerceIn(0, max)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0); say("Volume ${v * 100 / max} percent.")
            }
            calc != null -> {
                val a = calc.groupValues[1].toDouble(); val b = calc.groupValues[3].toDouble()
                val r = when (calc.groupValues[2]) { "plus", "+" -> a + b; "minus", "-" -> a - b; "times", "multiplied by", "x", "*" -> a * b; else -> if (b == 0.0) Double.NaN else a / b }
                say(if (r.isNaN()) "I can't divide by zero." else "That's " + (if (r % 1.0 == 0.0) r.toLong().toString() else "%.4f".format(r).trimEnd('0')))
            }
            has("remind me") -> {
                val unit = if (has("hour")) 3600 else if (has("second")) 1 else 60
                val task = t.substringAfter(" to ", "").replace(Regex("\\s*in \\d+ (second|minute|hour)s?"), "").trim()
                if (num == null || task.isEmpty()) say("Say, remind me in 10 minutes to call mom.") else {
                    Handler(Looper.getMainLooper()).postDelayed({ say("Reminder: $task") }, num * unit * 1000L)
                    say("Okay, I'll remind you in $num ${if (unit == 3600) "hours" else if (unit == 1) "seconds" else "minutes"} to $task.")
                }
            }
            has("weather") -> { start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, t)); say("Here's the weather.") }
            has("wifi") || has("wi-fi") || has("bluetooth") || has("airplane") || has("brightness") || has("settings") -> {
                val a = when {
                    has("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS; has("airplane") -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                    has("brightness") -> Settings.ACTION_DISPLAY_SETTINGS; has("settings") -> Settings.ACTION_SETTINGS
                    else -> Settings.ACTION_WIFI_SETTINGS }
                start(Intent(a)); say("Opening settings.")
            }
            has("good morning") -> say("Good morning! Hope you have a great day.")
            has("good night") -> say("Good night! Sleep well.")
            has("i love you") -> say("That's kind of you. I'm here whenever you need me.")
            verb != null -> openApp(verb.groupValues[1])
            has("flashlight") || has("torch") -> {
                val on = !words.contains("off")
                try {
                    val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    cm.setTorchMode(cm.cameraIdList[0], on); say("Flashlight " + if (on) "on." else "off.")
                } catch (e: Exception) { say("I can't reach the flashlight.") }
            }
            has("battery") -> {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                say("Battery is at ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} percent.")
            }
            note != null -> {
                val sp = ctx.getSharedPreferences("notes", Context.MODE_PRIVATE)
                sp.edit().putString("n", (sp.getString("n", "") ?: "") + note.groupValues[2] + ". ").apply(); say("Saved.")
            }
            has("my notes") || has("read notes") || has("read my note") ->
                say(ctx.getSharedPreferences("notes", Context.MODE_PRIVATE).getString("n", "")?.ifEmpty { null } ?: "You have no notes.")
            has("clear notes") -> { ctx.getSharedPreferences("notes", Context.MODE_PRIVATE).edit().clear().apply(); say("Notes cleared.") }
            Regex("^(search|google)\\s+(.+)").containsMatchIn(t) ->
                start(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", t.substringAfter(" ")))
            has("your name") || has("who are you") -> say("I'm your personal assistant. You built me!")
            has("how are you") -> say("I'm doing great, thanks for asking.")
            has("thank") -> say("You're welcome!")
            has("joke") -> say(listOf(
                "Why did the phone go to school? To get smarter.",
                "I told my phone a joke, but it didn't get the reception.",
                "Why was the computer cold? It left its Windows open.").random())
            has("help") || has("what can you do") ->
                say("Try: call Rahul. Message Rahul saying hello. Navigate to the airport. Play music. Volume up. What is 15 times 12. Remind me in 10 minutes to cook. Set a timer for 5 minutes. Open WhatsApp. Flashlight on. Tell me a joke. Or ask me anything and I will search the web.")
            words.any { it == "hi" || it == "hello" || it == "hey" } -> say("Hello! How can I help?")
            else -> askClaude(t)
        }
    }

    private val hindi = mapOf("समय" to "time", "टाइमर" to "timer", "अलार्म" to "alarm", "कॉल" to "call", "फोन" to "call",
        "खोलो" to "open", "खोलें" to "open", "बैटरी" to "battery", "टॉर्च" to "flashlight", "फ्लैशलाइट" to "flashlight",
        "मौसम" to "weather", "चुटकुला" to "joke", "आवाज" to "volume", "वॉल्यूम" to "volume", "गाना" to "play music",
        "संगीत" to "play music", "तारीख" to "date", "आज" to "today", "मैसेज" to "message", "संदेश" to "message",
        "याद दिलाओ" to "remind me", "नेविगेट" to "navigate", "चालू" to "on", "बंद" to "off", "वाईफाई" to "wifi",
        "ब्लूटूथ" to "bluetooth", "सेटिंग" to "settings", "नमस्ते" to "hello", "धन्यवाद" to "thank you", "मदद" to "help",
        "घंटे" to "hour", "घंटा" to "hour", "सेकंड" to "second", "मिनट" to "minute", "व्हाट्सएप" to "whatsapp", "इंस्टाग्राम" to "instagram", "यूट्यूब" to "youtube", "फेसबुक" to "facebook", "जीमेल" to "gmail", "टेलीग्राम" to "telegram", "वॉट्सऐप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "मेसेज" to "message", "kholo" to "open", "khol do" to "open", "kholiye" to "open", "बढ़ाओ" to "up", "कम करो" to "down", "करो" to "")
    private val ones = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19)
    private val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private fun nums(s: String): String {
        val w = s.split(" "); val out = ArrayList<String>(); var i = 0
        while (i < w.size) {
            val tv = tens[w[i]]; val ov = ones[w[i]]
            if (tv != null) {
                val nx = if (i + 1 < w.size) ones[w[i + 1]] else null
                if (nx != null && nx in 1..9) { out.add((tv + nx).toString()); i += 2 } else { out.add(tv.toString()); i++ }
            } else if (ov != null) { out.add(ov.toString()); i++ } else { out.add(w[i]); i++ }
        }
        return out.joinToString(" ").replace(Regex("\\b([ap]) m\\b"), "$1m")
    }
    private fun norm(s: String): String { var r = s; for ((k, v) in hindi) r = r.replace(k, v); return nums(r.replace(Regex("\\s+"), " ").trim()).replace(Regex("^(.+) open$"), "open $1") }

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", "MyAssistant/1.0")
        return c.inputStream.bufferedReader().readText()
    }

    private fun freeAnswer(q: String) {
        val topic = q.replace(Regex("^(?:what is|what's|what are|who is|who's|who was|tell me about|explain|define|meaning of|where is|when did|when was|how does|how do|why)\\s+(?:the |a |an )?"), "").trim()
        val main = Handler(Looper.getMainLooper())
        Thread {
            val ans = try {
                val enc = java.net.URLEncoder.encode(topic, "UTF-8")
                val title = JSONArray(get("https://en.wikipedia.org/w/api.php?action=opensearch&limit=1&format=json&search=$enc")).getJSONArray(1).optString(0, "")
                if (title.isEmpty()) null else
                    JSONObject(get("https://en.wikipedia.org/api/rest_v1/page/summary/" + java.net.URLEncoder.encode(title, "UTF-8").replace("+", "%20")))
                        .optString("extract", "").split(". ").take(2).joinToString(". ").take(400).ifBlank { null }
            } catch (e: Exception) { null }
            main.post { say(ans ?: "I couldn't find that. Add a Groq or Claude key in Settings for full answers, or say search, then your question.") }
        }.start()
    }

    fun online(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun xaiModel(key: String): String {
        val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        sp.getString("xmodel", "")?.takeIf { it.isNotEmpty() }?.let { return it }
        var pick = "grok-4"
        try {
            val c = URL("https://api.x.ai/v1/models").openConnection() as HttpURLConnection
            c.setRequestProperty("Authorization", "Bearer $key"); c.connectTimeout = 8000; c.readTimeout = 8000
            val arr = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("data")
            val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
                .filter { it.startsWith("grok") && listOf("image", "video", "imagine", "vision", "embed").none { x -> it.contains(x) } }
            pick = ids.firstOrNull { it.contains("non-reasoning") } ?: ids.firstOrNull { it.contains("fast") } ?: ids.firstOrNull { it.contains("mini") }
                ?: ids.firstOrNull { it.startsWith("grok-4") } ?: ids.firstOrNull() ?: pick
            sp.edit().putString("xmodel", pick).apply()
        } catch (e: Exception) {}
        return pick
    }

    private fun callClaude(system: String, msgs: JSONArray, done: (String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val key = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("key", "") ?: ""
        Thread {
            val ans = try {
                val xai = key.startsWith("xai-")
                val groq = key.startsWith("gsk_") || xai
                val all = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                for (i in 0 until msgs.length()) all.put(msgs.get(i))
                val body = if (groq) JSONObject().put("model", if (xai) xaiModel(key) else GROQ_MODEL).put("messages", all).also { if (!xai) it.put("max_tokens", 700) }
                    else JSONObject().put("model", "claude-haiku-5-5").put("max_tokens", 700).put("system", system).put("messages", msgs)
                val c = URL(if (xai) "https://api.x.ai/v1/chat/completions" else if (groq) "https://api.groq.com/openai/v1/chat/completions" else "https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 30000
                if (groq) c.setRequestProperty("Authorization", "Bearer $key") else { c.setRequestProperty("x-api-key", key); c.setRequestProperty("anthropic-version", "2023-06-01") }
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val ok = c.responseCode in 200..299
                val txt = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                if (ok) { if (groq) JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                          else JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text") }
                else { if (xai) ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().remove("xmodel").apply(); "The AI service returned error ${c.responseCode}: ${txt.take(150)}. Check your key in Settings." }
            } catch (e: Exception) { "I couldn't reach the AI service (${e.javaClass.simpleName}). Check your internet." }
            main.post { done(ans) }
        }.start()
    }

    private fun askClaude(q: String) {
        if (lowConf) { say("Sorry, I couldn't hear that clearly. Please say it again."); return }
        val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (!online()) { say("I'm offline right now, so I can only do phone commands, notes, reminders and calculations."); return }
        if ((sp.getString("key", "") ?: "").isBlank()) { freeAnswer(q); return }
        val msgs = JSONArray()
        Chat.hist.takeLast(12).forEach { msgs.put(JSONObject().put("role", it.first).put("content", it.second)) }
        msgs.put(JSONObject().put("role", "user").put("content", q))
        val mem = sp.getString("mem", "") ?: ""
        callClaude("You are Brother, a male personal voice assistant for an Indian user. Understand English, Hindi and Hinglish and reply in the user's language. Answer in one to three short spoken sentences unless asked for detail. No markdown or lists. User-approved preferences: $mem", msgs) {
            Chat.hist.add("user" to q); Chat.hist.add("assistant" to it); say(it)
        }
    }

    private fun emailDraft(req: String) {
        val key = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("key", "") ?: ""
        if (!online() || key.isBlank()) { say("Email drafting needs internet and a Groq or Claude key in Settings."); return }
        callClaude("Write a professional email for the user's request. Reply with only JSON: {\"subject\":\"...\",\"body\":\"...\"}. No markdown.",
            JSONArray().put(JSONObject().put("role", "user").put("content", req))) { r ->
            try {
                val j = JSONObject(r.substring(r.indexOf('{'), r.lastIndexOf('}') + 1))
                start(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_SUBJECT, j.getString("subject")).putExtra(Intent.EXTRA_TEXT, j.getString("body")))
                say("I've opened a draft email. Please review it and send it yourself.")
            } catch (e: Exception) { say("I couldn't prepare that email. Please try again.") }
        }
    }

    private fun key(code: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun dial(num: String) {
        val act = if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) Intent.ACTION_CALL else Intent.ACTION_DIAL
        start(Intent(act, Uri.parse("tel:" + Uri.encode(num))))
    }

    private fun waInstalled() = try { ctx.packageManager.getLaunchIntentForPackage("com.whatsapp") != null } catch (e: Exception) { false }

    private fun lead(s: String): String {
        val m = Regex("^(?:open|launch|start)\\s+(\\w+)\\s+and\\s+(?=(?:send|message|text|call|search|play|navigate)\\b)").find(s) ?: return s
        val rest = s.substring(m.range.last + 1)
        return if (m.groupValues[1] == "whatsapp" && !rest.contains("whatsapp")) "$rest on whatsapp" else rest
    }

    private fun parseMsg(s: String): Pair<List<Pair<String, String>>, Boolean>? {
        if (!Regex("\\b(?:message|text|sms)\\b").containsMatchIn(s)) return null
        val wa = s.contains("whatsapp") || (!Regex("\\b(?:sms|text message)\\b").containsMatchIn(s) && waInstalled())
        val c = s.replace(Regex("\\b(?:on|via|in|using|through)\\s+(?:my\\s+|the\\s+)?(?:whatsapp|sms)\\b"), " ").replace("whatsapp", " ")
            .replace(Regex("^(?:(?:please|can you|could you|brother|hey|ok)\\s+)+"), "").replace(Regex("\\s+"), " ").trim()
        val v = "(?:send\\s+)?(?:an?\\s+)?(?:message|text|sms)"
        val out = ArrayList<Pair<String, String>>()
        fun add(r: String, ni: Int, bi: Int) { Regex(r).find(c)?.let { out.add(it.groupValues[ni].trim() to (if (bi > 0) it.groupValues[bi].trim() else "")) } }
        add("^$v\\s+to\\s+(.+?)\\s+(?:saying|that says|that|say)\\s+(.+)$", 1, 2)
        add("^$v\\s+(.+?)\\s+(?:saying|that says|say)\\s+(.+)$", 1, 2)
        add("^$v\\s+to\\s+(.+)$", 1, 0)
        add("^$v\\s+(.+?)\\s+to\\s+(.+)$", 2, 1)
        add("^(?:message|text|sms)\\s+(\\w+)\\s*(.*)$", 1, 2)
        return if (out.isEmpty()) null else out to wa
    }

    private fun sendMsg(name: String, num: String, body: String, wa: Boolean) {
        if (wa) {
            var n = num.filter { it.isDigit() }; if (n.length == 10) n = "91$n"
            val auto = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("autosend", true) && SendAccessibility.enabled(ctx)
            if (auto) SendAccessibility.armedUntil = System.currentTimeMillis() + 20000
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n?text=" + Uri.encode(body)))
            if (waInstalled()) i.setPackage("com.whatsapp")
            start(i)
            say(if (auto) "Sending your WhatsApp message to $name." else "Opening WhatsApp chat with $name. Turn on auto-send in Permissions to send hands-free, or tap send.")
        } else {
            start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num))).putExtra("sms_body", body))
            say("Opening a message to $name. Tap send.")
        }
    }

    private fun findContact(name: String): Triple<String, String, Int>? {
        if (name.length < 2 || ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val q = name.lowercase().trim(); val qt = q.split(" ").filter { it.length > 1 }
        var best: Triple<String, String, Int>? = null
        val p = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        ctx.contentResolver.query(p, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0)?.lowercase() ?: continue; val num = c.getString(1) ?: continue
                val nt = n.split(" ", "-", "_", ".")
                val sc = when {
                    n == q -> 100
                    qt.isNotEmpty() && qt.all { x -> nt.contains(x) } -> 90
                    q.length >= 3 && n.contains(q) -> 80
                    qt.isNotEmpty() && qt.all { x -> nt.any { it.startsWith(x) || (x.length >= 4 && lev(it, x) <= 1) } } -> 70
                    n.length >= 4 && q.contains(n) -> 60
                    else -> 0
                }
                if (sc > (best?.third ?: 0)) best = Triple(n, num, sc)
            }
        }
        return best
    }

    private val dev = mapOf("कॉल" to "call", "फोन" to "call", "खोलो" to "kholo", "खोल" to "khol", "मैसेज" to "message", "मेसेज" to "message",
        "भेजो" to "bhejo", "भेज" to "bhej", "व्हाट्सएप" to "whatsapp", "वॉट्सऐप" to "whatsapp", "व्हाट्सऐप" to "whatsapp",
        "इंस्टाग्राम" to "instagram", "यूट्यूब" to "youtube", "को" to "ko", "पर" to "par", "में" to "me")
    private val stopW = setOf("send", "message", "call", "dial", "phone", "whatsapp", "text", "open", "launch", "start", "to", "ko", "on", "in", "via",
        "the", "a", "and", "aur", "saying", "say", "that", "ki", "bhejo", "bhej", "likho", "karo", "kar", "do", "please", "hi", "hello", "hey")

    private class Hit(val name: String, val num: String, val score: Double, val a: Int, val b: Int)
    private class WP(val intent: String, val name: String, val num: String, val body: String)

    private fun allContacts(): List<Pair<String, String>> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0)?.lowercase()?.trim() ?: continue; val num = c.getString(1) ?: continue
                if (n.isNotEmpty()) out.add(n to num)
            }
        }
        return out.distinctBy { it.first + it.second.filter { ch -> ch.isDigit() } }
    }

    private fun sim(x: String, y: String): Double {
        val a = x.replace(" ", ""); val b = y.replace(" ", "")
        return 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length, 1)
    }

    private fun wnorm(t: String): String {
        var s = t.lowercase()
        for ((k, v) in dev) s = s.replace(k, " $v ")
        s = s.replace(Regex("whats\\s*app|what's\\s*app|watsapp|whatsap|vatsapp|wotsap"), "whatsapp")
            .replace(Regex("\\b(massage|messege|mesage|msg|sms)\\b"), "message")
            .replace(Regex("[.,!?;:\"“”]"), " ").replace(Regex("\\s+"), " ").trim()
        return s.replace(Regex("^(hey |hi |hello |ok |okay )?(brother|bhai|bro)\\b[ ,]*"), "").trim()
    }

    private fun webFind(s: String, list: List<Pair<String, String>>): Hit? {
        val w = s.split(" "); var best: Hit? = null
        for ((name, num) in list) {
            val nt = name.split(" ").filter { it.isNotEmpty() }
            for (len in 1..minOf(3, nt.size + 1)) for (i in 0..(w.size - len)) {
                val win = w.subList(i, i + len)
                if (win.any { it in stopW }) continue
                var sc = sim(win.joinToString(" "), name)
                if (sc < (if (name.length <= 4) 0.8 else 0.62)) sc = 0.0
                if (win.all { x -> x.length >= 3 && nt.contains(x) }) sc = maxOf(sc, 0.9)
                if (sc > 0.0 && (best == null || sc > best.score)) best = Hit(name, num, sc, i, i + len)
            }
        }
        return best
    }

    private fun clean(t: String): String {
        var r = t.trim() + " "
        r = r.replace(Regex("^(?:open|launch|start)\\s+whatsapp\\s+(?:and|aur)?\\s*"), "")
        r = r.replace(Regex("^(?:send\\s+)?(?:an?\\s+)?(?:whatsapp\\s+)?(?:message\\s+|text\\s+)"), "")
        r = r.replace(Regex("^(?:(?:ko|to|ka|ke|se|please|plz|and|aur|message|whatsapp|text|send|bhejo|bhej|likho|karo|kar|do|dena|ye|ki|that|saying|say)\\s+)+"), "")
        r = r.trim().replace(Regex("(?:\\s+(?:on|in|via)\\s+whatsapp|\\s+(?:please|plz|bhejo|bhej|karo|kar|do|dena|bolo|bol|whatsapp))+$"), "")
        return r.replace(Regex("\\s+"), " ").trim()
    }

    private fun bodyOf(s: String, h: Hit): String {
        val w = s.split(" ")
        val after = w.drop(h.b).joinToString(" ")
        Regex("\\b(?:saying|that says|says|say|ki)\\s+(.+)$").find(after)?.let { return clean(it.groupValues[1]) }
        val before = w.take(h.a).joinToString(" ")
        val viaTo = h.a >= 1 && (w[h.a - 1] == "to" || w[h.a - 1] == "ko")
        val b1 = clean(before.replace(Regex("\\s*\\b(to|ko)$"), ""))
        if (viaTo && b1.isNotEmpty()) return b1
        val a1 = clean(after)
        return if (a1.isNotEmpty()) a1 else b1
    }

    private fun webParse(raw: String): WP? {
        val s = wnorm(raw); if (s.isEmpty()) return null
        if (Regex("^(note|remember|always|play|search|google|navigate)\\b|\\bremind me\\b|\\btimer\\b|\\balarm\\b").containsMatchIn(s)) return null
        val hasCall = Regex("\\b(call|dial|phone)\\b").containsMatchIn(s)
        val hasMsg = Regex("\\b(send|bhejo|bhej|likho|message|whatsapp|text)\\b").containsMatchIn(s)
        if (!hasCall && !hasMsg) return null
        val s2 = s.replace(Regex("\\b(?:on|in|via|using)\\s+(?:my\\s+)?whatsapp\\b"), " ").replace(Regex("\\s+"), " ").trim()
        val hit = webFind(s2, allContacts())
        if (hit == null) {
            if (hasCall || Regex("\\b(send|message|bhejo|bhej|likho)\\b").containsMatchIn(s)) Bus.onLog?.invoke("🔎 Contact not found in your phone for: $s")
            return null
        }
        val asCall = hasCall && !Regex("\\bmessage\\b").containsMatchIn(s)
        return if (asCall || !hasMsg) WP("call", hit.name, hit.num, "") else WP("whatsapp", hit.name, hit.num, bodyOf(s2, hit))
    }

    fun whisperHint(): String {
        val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val recent = (sp.getString("recent", "") ?: "").split("\n").filter { it.isNotBlank() }
        val names = (recent + allContacts().map { it.first }).distinct().filter { it.length in 2..22 }.take(24)
        return "Brother, open WhatsApp, call " + (names.firstOrNull() ?: "Himanshu") + ", send message hi to " + (names.getOrNull(1) ?: "Shivam") +
            " on WhatsApp, Instagram kholo. Contacts: " + names.joinToString(", ")
    }

    private fun recentAdd(name: String) {
        val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val l = listOf(name) + (sp.getString("recent", "") ?: "").split("\n")
        sp.edit().putString("recent", l.filter { it.isNotBlank() }.distinct().take(15).joinToString("\n")).apply()
    }

    private fun openApp(spoken: String) {
        val name = spoken.split(" ").filter { it !in setOf("my", "the", "app", "application", "please", "a") }
            .joinToString("").replace(" ", "")
        if (name.isEmpty()) { say("Which app?"); return }
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val alt = mapOf("notepad" to listOf("notes", "note"), "gallery" to listOf("photos"), "photos" to listOf("gallery"),
            "messages" to listOf("messaging", "message"), "browser" to listOf("chrome"), "maps" to listOf("googlemaps"),
            "contacts" to listOf("phone"), "music" to listOf("youtubemusic", "spotify"))
        val labels = apps.map { it to it.loadLabel(pm).toString().lowercase().replace(" ", "") }
        val hit = (listOf(name) + (alt[name] ?: emptyList())).firstNotNullOfOrNull { n -> labels.firstOrNull { it.second.contains(n) }?.first }
            ?: labels.filter { sim(name, it.second) >= 0.75 }.maxByOrNull { sim(name, it.second) }?.first
        val i = hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (i != null) { start(i); say("Opening ${hit?.loadLabel(pm)}.") } else say("I couldn't find an app called $name.")
    }

    private fun start(i: Intent) { try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { say("That didn't work on this phone.") } }
}

object Chat { val hist = mutableListOf<Pair<String, String>>() }

fun prepVoice(tts: TextToSpeech, ctx: Context, text: String) {
    val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    val hi = text.any { it in '\u0900'..'\u097F' }
    tts.language = Locale(if (hi) "hi" else "en", "IN")
    tts.setPitch(if (sp.getBoolean("deep", true)) 0.8f else 1f)
    if (!hi) {
        val saved = sp.getString("voice", "") ?: ""
        val vs = tts.voices ?: emptySet()
        val v = vs.firstOrNull { saved.isNotEmpty() && it.name == saved }
            ?: vs.filter { it.locale.language == "en" && it.locale.country == "IN" && it.features?.contains("notInstalled") != true }.maxByOrNull { it.quality }
        v?.let { tts.voice = it }
    }
}

object Bus { var onLog: ((String) -> Unit)? = null }

private const val ROUTER = "You are Brother, a male voice assistant on an Indian user's Android phone. The user's words come from speech recognition and may contain mistakes, Hindi or Hinglish; work out what they really meant. If they want a phone action, reply with exactly one line starting with CMD: followed by the command in simple English, using only these forms: set a timer for N minutes | set an alarm for H:MM am | what time is it | what is the date | call NAME | message NAME saying TEXT | message NAME on whatsapp saying TEXT | navigate to PLACE | open APP | play music | play SONG | pause | next song | previous song | volume up | volume down | volume N | flashlight on | flashlight off | battery level | note TEXT | read my notes | remind me in N minutes to TASK | write an email about TOPIC | search TEXT | open wifi settings | open bluetooth settings | what is the weather. For anything else, answer the question directly in one to three short spoken sentences in the user's language, with no markdown or lists. User preferences: "

private const val GROQ_MODEL = "llama-3.3-70b-versatile"

private var crashInstalled = false
fun installCrashLog(ctx: Context) {
    if (crashInstalled) return
    crashInstalled = true
    val prev = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        try { java.io.File(ctx.filesDir, "crash.txt").writeText(android.util.Log.getStackTraceString(e).take(1500)) } catch (x: Exception) {}
        prev?.uncaughtException(t, e)
    }
}

private fun lev(a: String, b: String): Int {
    val dp = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prev = dp[0]; dp[0] = i
        for (j in 1..b.length) { val tmp = dp[j]; dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = tmp }
    }
    return dp[b.length]
}
