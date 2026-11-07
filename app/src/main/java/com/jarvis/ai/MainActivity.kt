package com.jarvis.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    companion object {
        private const val REQ_MIC = 1
        private const val MODEL = "gemini-2.5-flash"
        private const val KEY_API = "api_key"
        private const val SYSTEM_PROMPT =
            "You are JARVIS, a witty, caring male best friend and voice assistant. " +
            "Reply in 1 to 3 short, natural sentences in the same language mix the user uses " +
            "(English, Hindi or Hinglish). No markdown, no emoji, no lists."
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private val history = mutableListOf<Pair<String, String>>()

    private lateinit var statusView: TextView
    private lateinit var heardView: TextView
    private lateinit var replyView: TextView
    private lateinit var keyInput: EditText
    private lateinit var micButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = createSecurePrefs()
        setContentView(buildUi())
        tts = TextToSpeech(this, this)

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(listener)
            }
        } else {
            setStatus("Speech recognition is not available on this phone.")
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            setStatus("Microphone permission is needed for voice.")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("en", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.language = Locale.US
            }
            tts.setPitch(0.9f)          // slightly deeper voice
            tts.setSpeechRate(1.0f)
        } else {
            setStatus("Text-to-speech failed to start.")
        }
    }

    // ---------- Secure storage ----------

    private fun createSecurePrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            this,
            "jarvis_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ---------- UI ----------

    private fun buildUi(): ScrollView {
        fun text(size: Float, color: Int) = TextView(this).apply {
            textSize = size
            setTextColor(color)
            setPadding(0, 16, 0, 16)
        }

        statusView = text(16f, Color.rgb(0, 229, 255)).apply { this.text = "Tap Speak and talk to me" }
        heardView = text(18f, Color.WHITE)
        replyView = text(20f, Color.rgb(170, 235, 255))

        micButton = Button(this).apply {
            this.text = "Speak"
            setOnClickListener { startListening() }
        }
        val stopButton = Button(this).apply {
            this.text = "Stop speaking"
            setOnClickListener { tts.stop() }
        }

        keyInput = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString(KEY_API, ""))
        }
        val saveButton = Button(this).apply {
            this.text = "Save key"
            setOnClickListener {
                prefs.edit().putString(KEY_API, keyInput.text.toString().trim()).apply()
                setStatus("API key saved securely.")
            }
        }
        val testButton = Button(this).apply {
            this.text = "Test connection"
            setOnClickListener { testConnection() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.rgb(8, 12, 20))
            addView(TextView(this@MainActivity).apply {
                this.text = "JARVIS"
                textSize = 28f
                setTextColor(Color.rgb(0, 229, 255))
            })
            addView(statusView)
            addView(heardView)
            addView(replyView)
            addView(micButton)
            addView(stopButton)
            addView(keyInput)
            addView(saveButton)
            addView(testButton)
        }
        return ScrollView(this).apply { addView(root) }
    }

    private fun setStatus(message: String) {
        statusView.text = message
    }

    private fun setIdle() {
        micButton.isEnabled = true
    }

    // ---------- Voice input ----------

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { setStatus("Listening...") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { setStatus("Processing...") }

        override fun onError(error: Int) {
            setIdle()
            setStatus(errorMessage(error))
        }

        override fun onResults(results: Bundle?) {
            setIdle()
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isEmpty()) setStatus("I did not catch that.") else handleSpeech(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: return
            heardView.text = "Hearing: $partial"
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        val rec = recognizer ?: return
        tts.stop()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        micButton.isEnabled = false
        rec.startListening(intent)
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "I did not catch that."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I did not hear anything."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice recognition needs an internet connection."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy, try again."
        else -> "Voice error $error. Try again."
    }

    // ---------- Command handling ----------

    private fun handleSpeech(text: String) {
        heardView.text = "You: $text"

        val local = runLocalCommand(text)
        if (local != null) {
            deliver(local)
            return
        }

        val key = prefs.getString(KEY_API, "").orEmpty()
        if (key.isEmpty()) {
            deliver("Please add your Gemini API key in the settings below first.")
            return
        }

        setStatus("Thinking...")
        val past = history.toList()
        Thread {
            try {
                val reply = askGemini(text, key, past)
                runOnUiThread {
                    history.add(text to reply)
                    deliver(reply)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    deliver("Sorry, I could not reach Gemini. ${e.message ?: ""}".trim())
                }
            }
        }.start()
    }

    private fun runLocalCommand(raw: String): String? {
        val t = raw.lowercase(Locale.ROOT)

        if (t.startsWith("open ") || t.contains("kholo")) {
            val target = t.replace("open", "").replace("kholo", "").trim()
            if (target.contains("camera")) {
                startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                return "Opening camera."
            }
            if (target.contains("setting")) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
                return "Opening settings."
            }
            val apps = linkedMapOf(
                "youtube" to "com.google.android.youtube",
                "chrome" to "com.android.chrome",
                "whatsapp" to "com.whatsapp",
                "instagram" to "com.instagram.android"
            )
            for ((name, pkg) in apps) {
                if (target.contains(name)) {
                    val intent = packageManager.getLaunchIntentForPackage(pkg)
                        ?: return "$name is not installed on this phone."
                    startActivity(intent)
                    return "Opening $name."
                }
            }
            return null
        }

        if (t.contains("flashlight on") || t.contains("torch on")) return setTorch(true)
        if (t.contains("flashlight off") || t.contains("torch off")) return setTorch(false)

        if (t.contains("time")) {
            val now = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            return "It is $now."
        }
        return null
    }

    private fun setTorch(on: Boolean): String {
        return try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This phone has no flashlight."
            cm.setTorchMode(id, on)
            if (on) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "I could not control the flashlight."
        }
    }

    // ---------- Gemini ----------

    private fun testConnection() {
        val key = keyInput.text.toString().trim().ifEmpty { prefs.getString(KEY_API, "").orEmpty() }
        if (key.isEmpty()) {
            setStatus("Enter a key first.")
            return
        }
        setStatus("Testing connection...")
        Thread {
            val msg = try {
                askGemini("Reply with just the word: connected", key, emptyList())
                "Connected to Gemini."
            } catch (e: Exception) {
                "Connection failed: ${e.message}"
            }
            runOnUiThread { setStatus(msg) }
        }.start()
    }

    /** Runs on a background thread. Throws on any failure. */
    private fun askGemini(userText: String, key: String, past: List<Pair<String, String>>): String {
        val contents = JSONArray()
        past.takeLast(10).forEach { (u, m) ->
            contents.put(turn("user", u))
            contents.put(turn("model", m))
        }
        contents.put(turn("user", userText))

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("contents", contents)

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key)
        }

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${raw.take(160)}")

            val parts = JSONObject(raw)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            return (0 until parts.length())
                .joinToString("") { parts.getJSONObject(it).optString("text") }
                .trim()
                .ifEmpty { "I have nothing to say to that." }
        } finally {
            conn.disconnect()
        }
    }

    private fun turn(role: String, text: String): JSONObject =
        JSONObject()
            .put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    // ---------- Output ----------

    private fun deliver(reply: String) {
        replyView.text = reply
        setStatus("Ready")
        tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "jarvis-reply")
    }
}
