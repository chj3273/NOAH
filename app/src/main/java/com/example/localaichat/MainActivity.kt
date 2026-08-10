package com.example.localaichat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var btnSelectLocalFile: Button
    private lateinit var btnSelectModel: Button
    private lateinit var btnResetChat: Button
    private lateinit var btnVoice: Button
    private lateinit var scrollView: ScrollView
    private lateinit var chatLayout: LinearLayout

    private val PREFS_NAME = "NOAH_PREFS"
    private val KEY_SELECTED_MODEL = "SELECTED_MODEL_ID"
    private val KEY_SELECTED_MODEL_NAME = "SELECTED_MODEL_NAME"
    private val KEY_API_KEY = "NVIDIA_API_KEY"
    private val KEY_LOCAL_MODEL_NAME = "LOCAL_MODEL_FILE_NAME"
    private val KEY_IS_LOCAL_MODE = "IS_LOCAL_AI_MODE"

    private val messageHistory = mutableListOf<Pair<String, Boolean>>()

    private var isLocalAiMode = false
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private var isTikiTakaActive = false

    private val selectLocalModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = getFileNameFromUri(uri) ?: "선택된 로컬 파일"
            isLocalAiMode = true

            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_LOCAL_MODEL_NAME, fileName)
                .putBoolean(KEY_IS_LOCAL_MODE, true)
                .apply()

            Toast.makeText(this, "로컬 모델 설정: $fileName", Toast.LENGTH_SHORT).show()
            updateModelDisplay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvModelName = findViewById(R.id.tvModelName)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnSetApiKey = findViewById(R.id.btnSetApiKey)
        btnSelectLocalFile = findViewById(R.id.btnSelectLocalFile)
        btnSelectModel = findViewById(R.id.btnSelectModel)
        btnResetChat = findViewById(R.id.btnResetChat)
        btnVoice = findViewById(R.id.btnVoice)
        scrollView = findViewById(R.id.scrollView)
        chatLayout = findViewById(R.id.chatLayout)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isLocalAiMode = prefs.getBoolean(KEY_IS_LOCAL_MODE, false)

        addMessageView("안녕하세요. NOAH AI입니다. 질문을 입력하거나 음성 대화를 사용해 보세요.", false)
        updateModelDisplay()

        tts = TextToSpeech(this, this)
        initSpeechRecognizer()
        checkAudioPermission()

        btnSetApiKey.setOnClickListener { showApiKeyDialog() }
        btnSelectLocalFile.setOnClickListener { selectLocalModelLauncher.launch(arrayOf("*/*")) }
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
            setPadding(36, 24, 36, 24)
            textSize = 15f
            maxWidth = 850
            if (isUser) {
                // 사용자 메시지: 밝은 무채색 (밝은 회색) 배경 + 검은색 글씨로 시인성 확보
                setTextColor(Color.BLACK)
                background = ColorDrawable(Color.parseColor("#E0E0E0"))
            } else {
                // AI 메시지: 어두운 무채색 (짙은 회색) 배경 + 흰색 글씨
                setTextColor(Color.WHITE)
                background = ColorDrawable(Color.parseColor("#2E2E2E"))
            }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(16, 12, 16, 12)
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
                        if (isTikiTakaActive) {
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
        // 활성화 상태도 원색(빨간색 등) 대신 뚜렷한 무채색(중간 회색)으로 통일
        btnVoice.setBackgroundColor(Color.parseColor("#666666"))
        startListening()
    }

    private fun stopTikiTakaMode() {
        isTikiTakaActive = false
        btnVoice.text = "음성"
        btnVoice.setBackgroundColor(Color.parseColor("#333333"))
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

    private fun speakText(text: String) {
        if (isTtsReady) {
            val cleanText = text.replace(Regex("[*#_~]"), "")
            tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "NOAH_UTTERANCE_ID")
        }
    }

    private fun processUserPrompt(prompt: String) {
        if (prompt.isEmpty()) return
        etInput.setText("")

        addMessageView(prompt, true)
        val aiTv = addMessageView("생성 중...", false)

        if (isLocalAiMode) {
            runLocalAiFallback(prompt, aiTv, "로컬 AI 모드 실행")
        } else {
            callNvidiaApiStreaming(prompt, aiTv)
        }
    }

    private fun updateModelDisplay() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (isLocalAiMode) {
            val localFileName = prefs.getString(KEY_LOCAL_MODEL_NAME, "로컬 모델")
            tvModelName.text = "Local: $localFileName"
        } else {
            val savedModelName = prefs.getString(KEY_SELECTED_MODEL_NAME, "Google Gemma 4 31B")
            tvModelName.text = "NVIDIA: $savedModelName"
        }
    }

    private fun showApiKeyDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val input = EditText(this).apply {
            setText(prefs.getString(KEY_API_KEY, ""))
            hint = "nvapi-..."
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this)
            .setTitle("NVIDIA API Key")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                prefs.edit().putString(KEY_API_KEY, input.text.toString().trim()).apply()
                Toast.makeText(this, "API Key 저장 완료", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showModelSelectionDialog() {
        val models = arrayOf("Google Gemma 4 31B", "Llama 3.1 8B", "OpenAI GPT-OSS 20B")
        val modelIds = arrayOf("google/gemma-4-31b-it", "meta/llama-3.1-8b-instruct", "openai/gpt-oss-20b")

        AlertDialog.Builder(this)
            .setTitle("온라인 모델 선택")
            .setItems(models) { _, which ->
                isLocalAiMode = false
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_SELECTED_MODEL, modelIds[which])
                    .putString(KEY_SELECTED_MODEL_NAME, models[which])
                    .putBoolean(KEY_IS_LOCAL_MODE, false)
                    .apply()
                updateModelDisplay()
            }
            .show()
    }

    private fun callNvidiaApiStreaming(prompt: String, aiTv: TextView) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val selectedModel = prefs.getString(KEY_SELECTED_MODEL, "google/gemma-4-31b-it") ?: "google/gemma-4-31b-it"

        if (apiKey.isEmpty()) {
            runLocalAiFallback(prompt, aiTv, "API 키가 없습니다. 설정해 주세요.")
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
                        put("content", "너는 한국어 AI 비서 NOAH이다. 특수문자나 이모티콘 없이 자연스러운 텍스트로만 대답하라.")
                    })
                    val history = messageHistory.takeLast(30)
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
                    put("stream", true)
                }

                conn.outputStream.use { os ->
                    os.write(jsonParam.toString().toByteArray(Charsets.UTF_8))
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                    val fullResponse = StringBuilder()
                    var line = reader.readLine()

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
                                    if (content.isNotEmpty()) {
                                        fullResponse.append(content)
                                        withContext(Dispatchers.Main) {
                                            aiTv.text = fullResponse.toString()
                                            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                                        }
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                        line = reader.readLine()
                    }

                    val finalAnswer = fullResponse.toString()
                    withContext(Dispatchers.Main) {
                        speakText(finalAnswer)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        runLocalAiFallback(prompt, aiTv, "API 오류 Code: ${conn.responseCode}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    runLocalAiFallback(prompt, aiTv, "네트워크 오류: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun runLocalAiFallback(prompt: String, aiTv: TextView, reason: String) {
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        val responseText = "로컬 AI 모드 답변입니다. ($reason)"
        aiTv.text = responseText
        speakText(responseText)
    }

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var fileName: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) fileName = cursor.getString(nameIndex)
            }
        }
        return fileName ?: uri.lastPathSegment
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
