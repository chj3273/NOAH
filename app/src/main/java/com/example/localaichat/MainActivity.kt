package com.example.localaichat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    private val KEY_API_KEY = "NVIDIA_API_KEY"

    private val messageHistory = mutableListOf<Pair<String, Boolean>>()

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

        addMessageView("안녕하세요. NOAH AI입니다. 질문을 입력하거나 음성 대화를 사용해 보세요.", false)
        updateModelDisplay()

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

    private fun resetChatHistory() {
        chatLayout.removeAllViews()
        messageHistory.clear()
        addMessageView("대화 내역이 초기화되었습니다. 무엇을 도와드릴까요?", false)
        Toast.makeText(this, "채팅 초기화 완료", Toast.LENGTH_SHORT).show()
    }

    private fun addMessageView(text: String, isUser: Boolean): TextView {
        messageHistory.add(Pair(text, isUser))

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

        addMessageView(prompt, true)
        val aiTv = addMessageView("생각 중...", false)

        callNvidiaApiStreaming(prompt, aiTv)
    }

    private fun updateModelDisplay() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedModelName = prefs.getString(KEY_SELECTED_MODEL_NAME, "Google Gemma 4 31B")
        tvModelName.text = "NVIDIA: $savedModelName"
    }

    private fun showApiKeyDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 0)
        }

        val input = EditText(this).apply {
            setText(prefs.getString(KEY_API_KEY, ""))
            hint = "nvapi-..."
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#888888"))
            background = createRoundedDrawable("#2E2E2E", 8f)
            setPadding(32, 24, 32, 24)
        }

        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("NVIDIA API Key")
            .setView(container)
            .setPositiveButton("저장") { _, _ ->
                prefs.edit().putString(KEY_API_KEY, input.text.toString().trim()).apply()
                Toast.makeText(this, "API Key 저장 완료", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showModelSelectionDialog() {
        val models = arrayOf("Nemotron 3 120B A12B", "llama 3.3 N 49B", "Gemma 4 31B", "GPT-OSS 20B")
        val modelIds = arrayOf("nvidia/nemotron-3-super-120b-a12b", "nvidia/llama-3.3-nemotron-super-49b-v1.5", "google/gemma-4-31b-it", "openai/gpt-oss-20b")

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

    private fun callNvidiaApiStreaming(prompt: String, aiTv: TextView) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val selectedModel = prefs.getString(KEY_SELECTED_MODEL, "nvidia/nemotron-3-super-120b-a12b") ?: "nvidia/nemotron-3-super-120b-a12b"

        if (apiKey.isEmpty()) {
            aiTv.text = "[오류] API 키가 설정되지 않았습니다.\n상단 '키' 버튼을 눌러 NVIDIA API 키를 입력해 주세요."
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://integrate.api.nvidia.com/v1/chat/completions")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "text/event-stream")
                    connectTimeout = 60000
                    readTimeout = 60000
                    doOutput = true
                }

                val messagesArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "너는 한국어 AI 비서 NOAH이다. 특수문자나 이모티콘 없이 자연스러운 구어체 형식의 텍스트로만 답변하라.")
                    })
                    val history = messageHistory.takeLast(25)
                    for ((msg, isUser) in history) {
                        if (msg != "생성 중...") {
                            put(JSONObject().apply {
                                put("role", if (isUser) "user" else "assistant")
                                put("content", msg)
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
                            if (data == "[DONE]") break

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
                    val errorStream = conn.errorStream
                    val errorLog = if (errorStream != null) {
                        BufferedReader(InputStreamReader(errorStream, Charsets.UTF_8)).use { it.readText() }
                    } else {
                        "상세 에러 내용 없음"
                    }
                    withContext(Dispatchers.Main) {
                        aiTv.text = "[API 오류 발생]\n코드: ${conn.responseCode}\n로그:\n$errorLog"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    aiTv.text = "[네트워크 오류 로그]\n${e.localizedMessage ?: e.toString()}"
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
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}
