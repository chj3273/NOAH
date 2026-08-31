package com.example.localaichat

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class ChatMessage(var id: Long = -1, val role: String, var content: String)

class ChatDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "noah_chat.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chat_history (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, content TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS chat_history")
        onCreate(db)
    }

    fun insertMessage(role: String, content: String): Long {
        val values = ContentValues().apply {
            put("role", role)
            put("content", content)
        }
        return writableDatabase.insert("chat_history", null, values)
    }

    fun updateMessage(id: Long, content: String) {
        val values = ContentValues().apply { put("content", content) }
        writableDatabase.update("chat_history", values, "id = ?", arrayOf(id.toString()))
    }

    @SuppressLint("Range")
    fun getAllMessages(): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        val cursor = readableDatabase.rawQuery("SELECT * FROM chat_history ORDER BY id ASC", null)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(cursor.getColumnIndex("id"))
            val role = cursor.getString(cursor.getColumnIndex("role"))
            val content = cursor.getString(cursor.getColumnIndex("content"))
            list.add(ChatMessage(id, role, content))
        }
        cursor.close()
        return list
    }

    fun clearAll() {
        writableDatabase.execSQL("DELETE FROM chat_history")
    }
}

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tvModelName: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnSetApiKey: Button
    private lateinit var btnSelectModel: Button
    private lateinit var btnResetChat: Button
    private lateinit var btnVoice: Button
    private lateinit var scrollView: ScrollView
    private lateinit var chatLayout: LinearLayout

    private val PREFS_NAME = "NOAH_PREFS"
    private val KEY_SELECTED_MODEL = "SELECTED_MODEL_ID"
    private val KEY_SELECTED_MODEL_NAME = "SELECTED_MODEL_NAME"
    private val KEY_API_KEY = "OPENROUTER_API_KEY"

    private lateinit var dbHelper: ChatDatabaseHelper
    private val messageHistory = mutableListOf<ChatMessage>()

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private var isTikiTakaActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.statusBarColor = Color.parseColor("#1A1A1A")

        tvModelName = findViewById(R.id.tvModelName)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnSetApiKey = findViewById(R.id.btnSetApiKey)
        btnSelectModel = findViewById(R.id.btnSelectModel)
        btnResetChat = findViewById(R.id.btnResetChat)
        btnVoice = findViewById(R.id.btnVoice)
        scrollView = findViewById(R.id.scrollView)
        chatLayout = findViewById(R.id.chatLayout)

        applyCustomComponentStyles()
        updateModelDisplay()

        dbHelper = ChatDatabaseHelper(this)
        loadChatHistoryFromDB()

        tts = TextToSpeech(this, this)
        initSpeechRecognizer()
        checkAudioPermission()

        btnSetApiKey.setOnClickListener { showApiKeyDialog() }
        btnSelectModel.setOnClickListener { showModelSelectionDialog() }
        btnResetChat.setOnClickListener { resetChatHistory() }

        btnVoice.setOnClickListener {
            if (isTikiTakaActive) stopTikiTakaMode() else startTikiTakaMode()
        }

        btnSend.setOnClickListener {
            stopTikiTakaMode()
            processUserPrompt(etInput.text.toString().trim())
        }
    }

    private fun applyCustomComponentStyles() {
        val allButtons = listOf(btnSetApiKey, btnSelectModel, btnResetChat, btnVoice, btnSend)
        allButtons.forEach { it.backgroundTintList = null }

        etInput.background = createRoundedDrawable("#2E2E2E", 10f)
        btnSend.background = createRoundedDrawable("#444444", 10f)
        btnVoice.background = createRoundedDrawable("#333333", 10f)

        val menuButtonBg = createRoundedDrawable("#333333", 8f)
        btnSetApiKey.background = menuButtonBg
        btnSelectModel.background = menuButtonBg
        btnResetChat.background = createRoundedDrawable("#262626", 8f)
    }

    private fun createRoundedDrawable(colorHex: String, cornerRadiusDp: Float): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = cornerRadiusDp * density
        }
    }

    private fun loadChatHistoryFromDB() {
        messageHistory.clear()
        val savedMessages = dbHelper.getAllMessages()
        for (msg in savedMessages) {
            messageHistory.add(msg)
            if (msg.content.isNotEmpty()) {
                addMessageView(msg.content, msg.role == "user")
            }
        }
    }

    private fun resetChatHistory() {
        chatLayout.removeAllViews()
        messageHistory.clear()
        dbHelper.clearAll()
        Toast.makeText(this, "채팅 내역이 모두 삭제되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun addMessageView(text: String, isUser: Boolean): TextView {
        val tv = TextView(this).apply {
            this.text = text
            setPadding(40, 26, 40, 26)
            textSize = 14.5f
            maxWidth = 850
            if (isUser) {
                setTextColor(Color.parseColor("#FFFFFF"))
                background = createRoundedDrawable("#383838", 16f)
            } else {
                setTextColor(Color.parseColor("#E5E5E5"))
                background = createRoundedDrawable("#222222", 16f)
            }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(8, 10, 8, 10)
            gravity = if (isUser) Gravity.END else Gravity.START
        }
        tv.layoutParams = params
        chatLayout.addView(tv)
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        return tv
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.KOREAN)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (isTikiTakaActive && utteranceId == "NOAH_FINAL_UTTERANCE") {
                            runOnUiThread { startListening() }
                        }
                    }
                    override fun onError(utteranceId: String?) {}
                })
            }
        }
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (isTikiTakaActive) startListening()
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    processUserPrompt(matches[0])
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startTikiTakaMode() {
        isTikiTakaActive = true
        btnVoice.text = "중지"
        btnVoice.backgroundTintList = null
        btnVoice.background = createRoundedDrawable("#606060", 10f)
        btnVoice.setTextColor(Color.WHITE)
        startListening()
    }

    private fun stopTikiTakaMode() {
        isTikiTakaActive = false
        btnVoice.text = "음성"
        btnVoice.backgroundTintList = null
        btnVoice.background = createRoundedDrawable("#333333", 10f)
        btnVoice.setTextColor(Color.WHITE)
        speechRecognizer.stopListening()
        if (tts.isSpeaking) tts.stop()
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkAudioPermission()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        }
        speechRecognizer.startListening(intent)
    }

    private fun processUserPrompt(prompt: String) {
        if (prompt.isEmpty()) return
        etInput.setText("")

        if (tts.isSpeaking) tts.stop()

        val userId = dbHelper.insertMessage("user", prompt)
        messageHistory.add(ChatMessage(userId, "user", prompt))
        addMessageView(prompt, true)

        val aiTv = addMessageView("생각 중...", false)
        val aiId = dbHelper.insertMessage("assistant", "")
        val aiMsg = ChatMessage(aiId, "assistant", "")
        messageHistory.add(aiMsg)

        callOpenRouterApiStreaming(aiTv, aiMsg)
    }

    private fun updateModelDisplay() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedModelName = prefs.getString(KEY_SELECTED_MODEL_NAME, "OpenRouter 자동 무료")
        tvModelName.text = "NOAH AI: $savedModelName"
    }

    private fun showApiKeyDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 0)
        }

        val input = EditText(this).apply {
            setText(prefs.getString(KEY_API_KEY, ""))
            hint = "sk-or-v1-..."
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#888888"))
            background = createRoundedDrawable("#2E2E2E", 8f)
            setPadding(32, 24, 32, 24)
        }

        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("OpenRouter API Key")
            .setView(container)
            .setPositiveButton("저장") { _, _ ->
                prefs.edit().putString(KEY_API_KEY, input.text.toString().trim()).apply()
                Toast.makeText(this, "API Key 저장 완료", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showModelSelectionDialog() {
        val models = arrayOf("OpenRouter 자동 무료", "Llama 3.3 70B (Free)", "Gemma 2 9B (Free)")
        val modelIds = arrayOf("openrouter/free", "meta-llama/llama-3.3-70b-instruct:free", "google/gemma-2-9b-it:free")

        AlertDialog.Builder(this)
            .setTitle("온라인 모델 선택")
            .setItems(models) { _, which ->
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_SELECTED_MODEL, modelIds[which])
                    .putString(KEY_SELECTED_MODEL_NAME, models[which])
                    .apply()
                updateModelDisplay()
            }
            .show()
    }

    private fun callOpenRouterApiStreaming(aiTv: TextView, aiMsg: ChatMessage) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val selectedModel = prefs.getString(KEY_SELECTED_MODEL, "openrouter/free") ?: "openrouter/free"

        if (apiKey.isEmpty()) {
            aiTv.text = "[오류] API 키가 설정되지 않았습니다.\n상단 '키' 버튼을 눌러 OpenRouter API 키를 입력해 주세요."
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://openrouter.ai/api/v1/chat/completions")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("HTTP-Referer", "https://github.com/NOAH-AI")
                    setRequestProperty("Accept", "text/event-stream")
                    connectTimeout = 60000
                    readTimeout = 60000
                    doOutput = true
                }

                val messagesArray = JSONArray().apply {
                    val history = messageHistory.dropLast(1).takeLast(30)
                    for (msg in history) {
                        if (msg.content.isNotEmpty()) {
                            put(JSONObject().apply {
                                put("role", msg.role)
                                put("content", msg.content)
                            })
                        }
                    }
                }

                val jsonParam = JSONObject().apply {
                    put("model", selectedModel)
                    put("messages", messagesArray)
                    put("temperature", 0.7)
                    put("max_tokens", 1024)
                    put("stream", true)
                }

                conn.outputStream.use { os ->
                    os.write(jsonParam.toString().toByteArray(Charsets.UTF_8))
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                    val fullResponse = StringBuilder()
                    var line = reader.readLine()

                    var isFirstToken = true
                    var lastSpokenIndex = 0
                    val punctuationSet = setOf('.', '?', '!', '\n')

                    if (isTtsReady) {
                        tts.speak("", TextToSpeech.QUEUE_FLUSH, null, "NOAH_FLUSH")
                    }

                    while (line != null) {
                        val currentLine = line
                        if (currentLine.startsWith("data: ")) {
                            val data = currentLine.removePrefix("data: ").trim()
                            if (data == "[DONE]") {
                                dbHelper.updateMessage(aiMsg.id, aiMsg.content)
                                break
                            }

                            try {
                                val jsonObject = JSONObject(data)
                                val choices = jsonObject.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val content = choices.getJSONObject(0).optJSONObject("delta")?.optString("content", "") ?: ""

                                    if (content.isNotEmpty() && content != "null") {
                                        var cleanedContent = content

                                        if (isFirstToken) {
                                            cleanedContent = content.trimStart()
                                            if (cleanedContent.isNotEmpty()) {
                                                isFirstToken = false
                                            }
                                        }

                                        if (cleanedContent.isNotEmpty()) {
                                            fullResponse.append(cleanedContent)
                                            aiMsg.content = fullResponse.toString()

                                            withContext(Dispatchers.Main) {
                                                aiTv.text = fullResponse.toString()
                                                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                                            }

                                            if (isTtsReady) {
                                                val currentText = fullResponse.toString()
                                                var i = lastSpokenIndex
                                                while (i < currentText.length) {
                                                    if (punctuationSet.contains(currentText[i])) {
                                                        val sentence = currentText.substring(lastSpokenIndex, i + 1).trim()
                                                        if (sentence.isNotEmpty() && sentence != "." && sentence != "?" && sentence != "!") {
                                                            val cleanSentence = sentence.replace(Regex("[*#_~]"), "")
                                                            val utteranceId = if (i >= currentText.length - 5) "NOAH_FINAL_UTTERANCE" else "NOAH_PART_${i}"
                                                            tts.speak(cleanSentence, TextToSpeech.QUEUE_ADD, null, utteranceId)
                                                        }
                                                        lastSpokenIndex = i + 1
                                                    }
                                                    i++
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                        line = reader.readLine()
                    }

                    if (isTtsReady && lastSpokenIndex < fullResponse.length) {
                        val remaining = fullResponse.substring(lastSpokenIndex).trim()
                        if (remaining.isNotEmpty()) {
                            val cleanRemaining = remaining.replace(Regex("[*#_~]"), "")
                            tts.speak(cleanRemaining, TextToSpeech.QUEUE_ADD, null, "NOAH_FINAL_UTTERANCE")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        aiTv.text = "[API 오류 발생]\n코드: ${conn.responseCode}"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    aiTv.text = "[네트워크 오류]\n${e.localizedMessage ?: e.toString()}"
                }
            }
        }
    }

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dbHelper.close()
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}
