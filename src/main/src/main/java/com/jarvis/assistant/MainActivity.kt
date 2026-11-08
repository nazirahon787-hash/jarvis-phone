package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sin

/** Pulsing orb that shows what the assistant is doing. */
class OrbView(context: Context) : View(context) {
    // 0 idle, 1 listening, 2 thinking, 3 speaking
    var mode = 0
    private var phase = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blue = Color.parseColor("#6C95FF")
    private val warm = Color.parseColor("#FF8F72")
    private val glowIn = Color.parseColor("#556C95FF")
    private val glowOut = Color.parseColor("#006C95FF")
    private val glossIn = Color.parseColor("#99FFFFFF")
    private val glossOut = Color.parseColor("#00FFFFFF")

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        phase += when (mode) {
            2 -> 0.12f
            3 -> 0.10f
            1 -> 0.08f
            else -> 0.02f
        }
        val amp = when (mode) {
            1, 3 -> 0.09f
            2 -> 0.03f
            else -> 0.02f
        }
        val cx = width / 2f
        val cy = height / 2f
        val r = Math.min(width, height) / 2f * 0.6f * (1f + amp * sin(phase * 2f))

        paint.shader = RadialGradient(cx, cy, r * 1.6f, intArrayOf(glowIn, glowOut), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 1.6f, paint)

        canvas.save()
        canvas.rotate(phase * 28f, cx, cy)
        paint.shader = SweepGradient(cx, cy, intArrayOf(blue, warm, blue), null)
        canvas.drawCircle(cx, cy, r, paint)
        canvas.restore()

        paint.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r, intArrayOf(glossIn, glossOut), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        postInvalidateOnAnimation()
    }
}

class MainActivity : Activity() {

    private data class HttpResult(val code: Int, val body: String)

    private val bg = Color.parseColor("#0D1420")
    private val surface = Color.parseColor("#16202F")
    private val fg = Color.parseColor("#E8EDF5")
    private val muted = Color.parseColor("#93A1B8")
    private val accent = Color.parseColor("#6C95FF")

    private lateinit var prefs: SharedPreferences
    private lateinit var orb: OrbView
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var mode = 0
    private var introCleared = false
    private var pendingAfterPermission: (() -> Unit)? = null
    private val history = ArrayList<Pair<String, String>>()

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val IDLE_TEXT = "Tap the orb and speak"
        const val REQ_MIC = 1
        const val REQ_CALL = 2
        const val REQ_CAL = 3
        const val SYSTEM_TEXT =
            "You are Jarvis, a friendly voice assistant on an Android phone, like Siri. " +
            "Reply in English with one to three short spoken sentences. " +
            "Never use markdown, lists, emoji or links. " +
            "If the user wants phone actions, tell them to say them directly, for example " +
            "'call Mom', 'set an alarm for 7 am', 'set a timer for 10 minutes' or 'what is on my calendar'. " +
            "You cannot browse the internet."
    }

    // ---------------------------------------------------------------- setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()

        tts = TextToSpeech(this, object : TextToSpeech.OnInitListener {
            override fun onInit(status: Int) {
                if (status == TextToSpeech.SUCCESS) {
                    tts?.setLanguage(Locale.US)
                    ttsReady = true
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            main.post { setMode(3, "Speaking...") }
                        }

                        override fun onDone(utteranceId: String?) {
                            main.post { setMode(0, IDLE_TEXT) }
                        }

                        override fun onError(utteranceId: String?) {
                            main.post { setMode(0, IDLE_TEXT) }
                        }
                    })
                }
            }
        })

        if ((prefs.getString("key", "") ?: "").isBlank()) {
            main.postDelayed({ showSettings() }, 500L)
        } else {
            handleLaunchIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(i: Intent?) {
        val a = i?.action
        if (a == Intent.ACTION_ASSIST || a == Intent.ACTION_VOICE_COMMAND) {
            main.postDelayed({ startListening() }, 600L)
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.setPadding(dp(16), dp(12), dp(16), dp(12))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        val title = TextView(this)
        title.text = "Jarvis"
        title.setTextColor(fg)
        title.textSize = 22f
        title.setTypeface(null, Typeface.BOLD)
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val settings = TextView(this)
        settings.text = "Settings"
        settings.setTextColor(accent)
        settings.textSize = 15f
        settings.setPadding(dp(12), dp(8), dp(4), dp(8))
        settings.setOnClickListener { showSettings() }
        top.addView(settings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        orb = OrbView(this)
        orb.setOnClickListener { onOrbTap() }
        root.addView(orb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))

        statusView = TextView(this)
        statusView.text = IDLE_TEXT
        statusView.setTextColor(muted)
        statusView.textSize = 15f
        statusView.gravity = Gravity.CENTER
        root.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        scroll = ScrollView(this)
        logView = TextView(this)
        logView.setTextColor(fg)
        logView.textSize = 16f
        logView.setLineSpacing(0f, 1.15f)
        logView.setPadding(0, dp(12), 0, dp(12))
        logView.text = "Try: \"what time is it\", \"what's on my calendar\", \"call Mom\", \"set an alarm for 7 am\", or ask anything."
        logView.setTextColor(muted)
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL

        input = EditText(this)
        input.hint = "Or type here"
        input.setHintTextColor(muted)
        input.setTextColor(fg)
        input.setBackgroundColor(surface)
        input.setPadding(dp(14), dp(10), dp(14), dp(10))
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setSingleLine(true)
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTyped()
                true
            } else {
                false
            }
        }
        bottom.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val send = Button(this)
        send.text = "Send"
        send.setOnClickListener { sendTyped() }
        bottom.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    private fun setMode(m: Int, text: String) {
        mode = m
        orb.mode = m
        statusView.text = text
    }

    private fun appendLog(who: String, text: String) {
        if (!introCleared) {
            introCleared = true
            logView.text = ""
        }
        logView.setTextColor(fg)
        logView.append(who + ": " + text + "\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------ settings

    private fun showSettings() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(0))

        val help = TextView(this)
        help.text = "Jarvis uses Google Gemini (free). Open aistudio.google.com/apikey, sign in with Google, tap Create API key, copy it and paste it below."
        help.textSize = 14f
        box.addView(help)

        val keyField = EditText(this)
        keyField.hint = "Gemini API key"
        keyField.setSingleLine(true)
        keyField.setText(prefs.getString("key", ""))
        box.addView(keyField)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("key", keyField.text.toString().trim()).apply()
                say("Saved. Tap the orb and talk to me.")
            }
            .setNeutralButton("Get free key") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                } catch (e: Exception) {
                    say("I could not open the browser.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------- voice in

    private fun onOrbTap() {
        when (mode) {
            1 -> recognizer?.stopListening()
            3 -> {
                tts?.stop()
                setMode(0, IDLE_TEXT)
            }
            2 -> {}
            else -> startListening()
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Speech recognition is not available on this phone. You can type instead.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAfterPermission = { startListening() }
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        tts?.stop()
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(listener)
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        setMode(1, "Listening...")
        recognizer?.startListening(i)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!p.isNullOrBlank()) {
                statusView.text = p
            }
        }

        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (t.isNullOrBlank()) {
                setMode(0, IDLE_TEXT)
            } else {
                handleCommand(t)
            }
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    setMode(0, "I didn't catch that. Tap the orb and try again.")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    setMode(0, "Speech recognition needs internet. Check your connection.")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    setMode(0, "Please allow the microphone for Jarvis in phone settings.")
                else -> setMode(0, "Something went wrong with the microphone. Try again.")
            }
        }
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        tts?.stop()
        handleCommand(t)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        val action = pendingAfterPermission
        pendingAfterPermission = null
        if (ok && action != null) {
            action()
        } else if (!ok) {
            say("I need that permission to do this. You can allow it in phone settings.")
        }
    }

    // ------------------------------------------------------------ commands

    private fun handleCommand(raw: String) {
        appendLog("You", raw)
        setMode(2, "Thinking...")
        var l = raw.lowercase(Locale.US).replace(".", "").trim()
        l = l.removePrefix("hey jarvis").removePrefix("jarvis").removePrefix("please").trim(' ', ',')

        val timeRe = Regex("""\b(what time is it|what is the time|what's the time|current time|tell me the time|time now)\b""")
        val dateRe = Regex("""\b(what is the date|what's the date|today's date|what day is it|what day is today)\b|^(what is|what's) today$""")
        val calRe = Regex("""\b(calendar|schedule|my events|meetings|appointments|agenda)\b""")
        val callRe = Regex("""^(?:call|phone|dial|ring)\s+(.+)$""")
        val alarmRe = Regex("""alarm\s+(?:for|at)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""")
        val timerRe = Regex("""timer\s+(?:for\s+)?(\d+)\s*(second|sec|minute|min|hour|hr)""")

        val callMatch = callRe.find(l)
        val alarmMatch = alarmRe.find(l)
        val timerMatch = timerRe.find(l)

        when {
            timeRe.containsMatchIn(l) -> {
                say("It's " + SimpleDateFormat("h:mm a", Locale.US).format(Date()) + ".")
            }
            dateRe.containsMatchIn(l) -> {
                say("Today is " + SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date()) + ".")
            }
            calRe.containsMatchIn(l) -> {
                when {
                    l.contains("tomorrow") -> readCalendarWithPermission(1, 1, "tomorrow")
                    l.contains("week") -> readCalendarWithPermission(0, 7, "this week")
                    else -> readCalendarWithPermission(0, 1, "today")
                }
            }
            callMatch != null -> {
                callTarget(callMatch.groupValues[1].trim())
            }
            alarmMatch != null -> {
                setAlarm(alarmMatch.groupValues[1], alarmMatch.groupValues[2], alarmMatch.groupValues[3])
            }
            timerMatch != null -> {
                setTimer(timerMatch.groupValues[1], timerMatch.groupValues[2])
            }
            else -> askGemini(raw)
        }
    }

    private fun say(text: String) {
        appendLog("Jarvis", text)
        val clean = text.replace(Regex("[*_#`>~]"), "")
        if (ttsReady) {
            setMode(3, "Speaking...")
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        } else {
            setMode(0, IDLE_TEXT)
        }
    }

    // calendar

    private fun readCalendarWithPermission(startDay: Int, span: Int, label: String) {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            pendingAfterPermission = { say(readCalendar(startDay, span, label)) }
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ_CAL)
            return
        }
        say(readCalendar(startDay, span, label))
    }

    private fun readCalendar(startDay: Int, span: Int, label: String): String {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, startDay)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, span)
        val end = cal.timeInMillis

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start)
        ContentUris.appendId(builder, end)

        val cursor = contentResolver.query(
            builder.build(),
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY
            ),
            null,
            null,
            CalendarContract.Instances.BEGIN + " ASC"
        ) ?: return "I couldn't read your calendar."

        val fmt = SimpleDateFormat(if (span > 1) "EEEE h:mm a" else "h:mm a", Locale.US)
        val items = ArrayList<String>()
        cursor.use {
            while (it.moveToNext() && items.size < 6) {
                val title = it.getString(0) ?: "Untitled"
                val begin = it.getLong(1)
                val allDay = it.getInt(2) == 1
                if (allDay) {
                    items.add(title + ", all day")
                } else {
                    items.add(title + " at " + fmt.format(Date(begin)))
                }
            }
        }
        if (items.isEmpty()) {
            return "You have nothing on your calendar " + label + "."
        }
        val n = items.size
        val noun = if (n == 1) "event" else "events"
        return "You have " + n + " " + noun + " " + label + ": " + items.joinToString(". ") + "."
    }

    // calls

    private fun callTarget(target: String) {
        val digits = target.replace(Regex("[^0-9+]"), "")
        val isNumber = digits.length >= 3 && Regex("[a-z]").containsMatchIn(target).not()

        val missing = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CALL_PHONE)
        }
        if (!isNumber && checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_CONTACTS)
        }
        if (missing.isNotEmpty()) {
            pendingAfterPermission = { callTarget(target) }
            requestPermissions(missing.toTypedArray(), REQ_CALL)
            return
        }

        if (isNumber) {
            dial(digits, digits)
            return
        }
        val found = findContact(target)
        if (found == null) {
            say("I couldn't find " + target + " in your contacts.")
        } else {
            dial(found.second, found.first)
        }
    }

    private fun findContact(name: String): Pair<String, String>? {
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%" + name + "%"),
            null
        ) ?: return null
        cursor.use {
            if (it.moveToFirst()) {
                return Pair(it.getString(0) ?: name, it.getString(1) ?: "")
            }
        }
        return null
    }

    private fun dial(number: String, label: String) {
        if (number.isBlank()) {
            say("That contact has no phone number.")
            return
        }
        say("Calling " + label + ".")
        main.postDelayed({
            try {
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))))
            } catch (e: Exception) {
                say("I couldn't start the call.")
            }
        }, 1400L)
    }

    // alarm and timer

    private fun setAlarm(hourText: String, minuteText: String, meridiem: String) {
        var hour = hourText.toIntOrNull() ?: 0
        val minute = if (minuteText.isEmpty()) 0 else (minuteText.toIntOrNull() ?: 0)
        if (meridiem == "pm" && hour < 12) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0
        if (hour > 23 || minute > 59) {
            say("That time doesn't look right. Please try again.")
            return
        }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
        i.putExtra(AlarmClock.EXTRA_HOUR, hour)
        i.putExtra(AlarmClock.EXTRA_MINUTES, minute)
        i.putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        try {
            startActivity(i)
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, hour)
            c.set(Calendar.MINUTE, minute)
            say("Alarm set for " + SimpleDateFormat("h:mm a", Locale.US).format(c.time) + ".")
        } catch (e: Exception) {
            say("I couldn't set the alarm on this phone.")
        }
    }

    private fun setTimer(amountText: String, unit: String) {
        val amount = amountText.toIntOrNull() ?: 0
        val seconds = when {
            unit.startsWith("hour") || unit.startsWith("hr") -> amount * 3600
            unit.startsWith("min") -> amount * 60
            else -> amount
        }
        if (seconds <= 0) {
            say("Please tell me how long the timer should run.")
            return
        }
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
        i.putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        i.putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        try {
            startActivity(i)
            say("Timer started for " + amount + " " + unit + (if (amount == 1) "" else "s") + ".")
        } catch (e: Exception) {
            say("I couldn't start a timer on this phone.")
        }
    }

    // ------------------------------------------------------------- Gemini

    private fun askGemini(userText: String) {
        val key = prefs.getString("key", "") ?: ""
        if (key.isBlank()) {
            say("Please add your free Gemini key in Settings first.")
            showSettings()
            return
        }
        history.add(Pair("user", userText))
        while (history.size > 12) history.removeAt(0)
        while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)

        val snapshot = ArrayList<Pair<String, String>>(history)
        thread {
            val result: Pair<String, Boolean>? = try {
                callGemini(key, snapshot)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (result == null) {
                    dropLastTurn()
                    say("I couldn't reach the internet. Check your connection and try again.")
                } else {
                    if (result.second) {
                        history.add(Pair("model", result.first))
                    } else {
                        dropLastTurn()
                    }
                    say(result.first)
                }
            }
        }
    }

    private fun dropLastTurn() {
        if (history.isNotEmpty()) history.removeAt(history.size - 1)
    }

    /** Returns the reply text and whether it succeeded. */
    private fun callGemini(key: String, turns: List<Pair<String, String>>): Pair<String, Boolean> {
        val contents = JSONArray()
        for ((index, turn) in turns.withIndex()) {
            val text = if (index == 0) SYSTEM_TEXT + "\n\n" + turn.second else turn.second
            val part = JSONObject().put("text", text)
            val item = JSONObject()
                .put("role", turn.first)
                .put("parts", JSONArray().put(part))
            contents.put(item)
        }
        val body = JSONObject()
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", 400).put("temperature", 0.7))
            .toString()

        var model = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        var res = post(key, model, body)

        if (res.code == 404) {
            val found = discoverModel(key)
            if (found != null) {
                model = found
                prefs.edit().putString("model", model).apply()
                res = post(key, model, body)
            }
        }

        if (res.code == 429) {
            return Pair("The free limit was reached. Please wait a minute and try again.", false)
        }
        if (res.code == 400 || res.code == 401 || res.code == 403) {
            return Pair("Google rejected the API key. Open Settings and check that you pasted it correctly.", false)
        }
        if (res.code !in 200..299) {
            return Pair("Gemini had a problem. Error code " + res.code + ".", false)
        }

        val json = JSONObject(res.body)
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return Pair("I couldn't answer that one. Try asking it differently.", false)
        }
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text", ""))
            }
        }
        val answer = sb.toString().replace(Regex("[*_#`>~]"), "").trim()
        if (answer.isEmpty()) {
            return Pair("I don't have an answer for that.", false)
        }
        return Pair(answer, true)
    }

    private fun post(key: String, model: String, body: String): HttpResult {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 45000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return HttpResult(code, text)
    }

    /** Finds a current Gemini "flash" model the key can use, if the saved one no longer exists. */
    private fun discoverModel(key: String): String? {
        return try {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("x-goog-api-key", key)
            val text = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val models = JSONObject(text).optJSONArray("models") ?: return null
            val skip = listOf("image", "tts", "live", "audio", "native", "lite", "embedding", "robotics", "computer")
            val names = ArrayList<String>()
            for (i in 0 until models.length()) {
                val m = models.getJSONObject(i)
                val name = m.optString("name", "").removePrefix("models/")
                val methods = m.optJSONArray("supportedGenerationMethods")
                var canGenerate = false
                if (methods != null) {
                    for (j in 0 until methods.length()) {
                        if (methods.getString(j) == "generateContent") canGenerate = true
                    }
                }
                if (canGenerate && name.contains("flash") && skip.none { name.contains(it) }) {
                    names.add(name)
                }
            }
            names.sortedDescending().firstOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
