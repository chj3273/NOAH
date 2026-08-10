package com.example.localaichat

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var btnSelectModel: Button
    private lateinit var btnSetApiKey: Button
    private lateinit var btnVoiceMode: Button
    private lateinit var llChatContainer: LinearLayout
    private lateinit var svChat: ScrollView

    private val PREFS_NAME = "NOAH_PREFS"
    private val KEY_SELECTED_MODEL = "SELECTED_MODEL_ID"
    private val KEY_SELECTED_MODEL_NAME = "SELECTED_MODEL_NAME"
    private val KEY_API_KEY = "NVIDIA_API_KEY"

    private val chatHistory = mutableListOf<Pair<String, String>>()
    private var isVoiceModeActive = false

    // STT & TTS
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private val REQUEST_RECORD_AUDIO_PERMISSION = 200

    private var currentAiBubbleView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvModelName = findViewById(R.id.tvModelName)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnSelectModel = findViewById(R.id.btnSelectModel)
        btnSetApiKey = findViewById(R.id.btnSetApiKey)
        btnVoiceMode = findViewById(R.id.btnVoiceMode)
        llChatContainer = findViewById(R.id.llChatContainer)
        svChat = findViewById(R.id.svChat)

        textToSpeech = TextToSpeech(this, this)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedModelName = prefs.getString(KEY_SELECTED_MODEL_NAME, "Google Gemma 4 31B")
        tvModelName.text = "NVIDIA NIM: $savedModelName"

        btnSetApiKey.setOnClickListener { showApiKeyDialog() }
        btnSelectModel.setOnClickListener { showModelSelectionDialog() }

        btnVoiceMode.setOnClickListener {
            checkPermissionAndToggleVoiceMode()
        }

        btnSend.setOnClickListener {
            val prompt = etInput.text.toString().trim()
            if (prompt.isNotEmpty()) {
                etInput.setText("")
                processUserMessage(prompt)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.KOREAN
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (isVoiceModeActive) {
                        Handler(Looper.getMainLooper()).post {
                            startSpeechRecognition()
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    if (isVoiceModeActive) {
                        Handler(Looper.getMainLooper()).post {
                            startSpeechRecognition()
                        }
                    }
                }
            })
        }
    }

    private fun checkPermissionAndToggleVoiceMode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
        } else {
            toggleVoiceMode()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleVoiceMode()
            } else {
                Toast.makeText(this, "마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleVoiceMode() {
        isVoiceModeActive = !isVoiceModeActive
        if (isVoiceModeActive) {
            btnVoiceMode.text = "연속 음성: ON"
            btnVoiceMode.setBackgroundColor(Color.parseColor("#444444"))
            Toast.makeText(this, "연속 음성 대화가 시작되었습니다.", Toast.LENGTH_SHORT).show()
            startSpeechRecognition()
        } else {
            btnVoiceMode.text = "연속 음성: OFF"
            btnVoiceMode.setBackgroundColor(Color.parseColor("#2A2A2A"))
            stopSpeechRecognizer()
            textToSpeech?.stop()
            Toast.makeText(this, "연속 음성 대화가 종료되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // 안드로이드 11(One UI 3.1) 호환 SpeechRecognizer 생성기
    private fun createSpeechRecognizerInstance(): SpeechRecognizer {
        val googleComponent = ComponentName(
            "com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.service.SpeechRecognitionService"
        )

        val intent = Intent("android.speech.RecognitionService").apply {
            component = googleComponent
        }
        val services = packageManager.queryIntentServices(intent, 0)

        return if (services.isNotEmpty()) {
            // 삼성 음성 입력(SMT) 먹통 현상 방지를 위해 구글 음성 인식 서비스 지정
            SpeechRecognizer.createSpeechRecognizer(this, googleComponent)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
    }

    private fun startSpeechRecognition() {
        if (!isVoiceModeActive) return

        // 이전 음성 인식 객체 리셋
        stopSpeechRecognizer()

        try {
            speechRecognizer = createSpeechRecognizerInstance().apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        // 안드로이드 11에서 무응답/타임아웃 발생 시 안전하게 재시도
                        if (isVoiceModeActive) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                startSpeechRecognition()
                            }, 800)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val spokenText = matches[0]
                            processUserMessage(spokenText)
                        } else if (isVoiceModeActive) {
                            startSpeechRecognition()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                // 안드로이드 11(One UI 3.1) 필수: 호출 패키지 명시
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {
            if (isVoiceModeActive) {
                Handler(Looper.getMainLooper()).postDelayed({
                    startSpeechRecognition()
                }, 1000)
            }
        }
    }

    private fun stopSpeechRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            speechRecognizer = null
        }
    }

    private fun processUserMessage(prompt: String) {
        addUserBubble(prompt)
        currentAiBubbleView = addAiBubble("답변 생성 중...")
        callNvidiaApiStreaming(prompt)
    }

    private fun addUserBubble(text: String) {
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.parseColor("#3B3B3B"))
            setPadding(28, 20, 28, 20)
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END
            bottomMargin = 24
            setMargins(100, 0, 0, 24)
        }

        llChatContainer.addView(bubble, params)
        scrollToBottom()
    }

    private fun addAiBubble(initialText: String): TextView {
        val bubble = TextView(this).apply {
            this.text = initialText
            setTextColor(Color.parseColor("#DDDDDD"))
            textSize = 14f
            setBackgroundColor(Color.parseColor("#222222"))
            setPadding(28, 20, 28, 20)
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.START
            bottomMargin = 24
            setMargins(0, 0, 100, 24)
        }

        llChatContainer.addView(bubble, params)
        scrollToBottom()
        return bubble
    }

    private fun scrollToBottom() {
        svChat.post {
            svChat.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun speakOut(text: String) {
        if (text.isEmpty()) return
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "NOAH_AI_RESPONSE")
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "NOAH_AI_RESPONSE")
    }

    private fun showApiKeyDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_API_KEY, "")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        val titleView = TextView(this).apply {
            text = "API Key 설정"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, 30)
        }
        container.addView(titleView)

        val input = EditText(this).apply {
            setText(savedKey)
            hint = "nvapi-..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#777777"))
            setBackgroundColor(Color.parseColor("#2C2C2C"))
            setPadding(30, 20, 30, 20)
            textSize = 14f
        }
        container.addView(input)

        AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton("저장") { _, _ ->
                val apiKey = input.text.toString().trim()
                prefs.edit().putString(KEY_API_KEY, apiKey).apply()
                Toast.makeText(this, "API Key가 저장되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showModelSelectionDialog() {
        val models = arrayOf(
            "Google Gemma 4 31B",
            "Llama 3.1 8B",
            "OpenAI GPT-OSS 20B"
        )
        val modelIds = arrayOf(
            "google/gemma-4-31b-it",
            "meta/llama-3.1-8b-instruct",
            "openai/gpt-oss-20b"
        )

        AlertDialog.Builder(this)
            .setTitle("AI 모델 선택")
            .setItems(models) { _, which ->
                val selectedId = modelIds[which]
                val selectedName = models[which]

                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_SELECTED_MODEL, selectedId)
                    .putString(KEY_SELECTED_MODEL_NAME, selectedName)
                    .apply()

                tvModelName.text = "NVIDIA NIM: $selectedName"
                Toast.makeText(this, "선택됨: $selectedName", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun callNvidiaApiStreaming(prompt: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val selectedModel = prefs.getString(KEY_SELECTED_MODEL, "google/gemma-4-31b-it") ?: "google/gemma-4-31b-it"

        if (apiKey.isEmpty()) {
            val errorMsg = "API Key가 설정되지 않았습니다. 상단 API Key 버튼을 눌러 입력해주세요."
            currentAiBubbleView?.text = errorMsg
            if (isVoiceModeActive) {
                speakOut("API 키를 설정해주세요.")
            }
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
                    connectTimeout = 7000
                    readTimeout = 7000
                    doOutput = true
                }

                val messagesArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "너는 한국어 AI 비서 NOAH이다. 답변 작성 시 이모지, 이모티콘, 별표(*), 특수문자 장식을 일절 사용하지 말고 오직 순수한 텍스트만 자연스럽게 작성하라.")
                    })
                    for ((user, assistant) in chatHistory.takeLast(10)) {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", user)
                        })
                        put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", assistant)
                        })
                    }
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                }

                val jsonParam = JSONObject().apply {
                    put("model", selectedModel)
                    put("messages", messagesArray)
                    put("temperature", 0.7)
                    put("top_p", 0.9)
                    put("max_tokens", 1024)
                    put("stream", true)
                }

                conn.outputStream.use { os ->
                    os.write(jsonParam.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                    var line: String?
                    val fullResponse = StringBuilder()

                    withContext(Dispatchers.Main) {
                        currentAiBubbleView?.text = ""
                    }

                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line ?: continue
                        if (currentLine.startsWith("data: ")) {
                            val data = currentLine.removePrefix("data: ").trim()
                            if (data == "[DONE]") break

                            try {
                                val jsonObject = JSONObject(data)
                                val choices = jsonObject.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                                    val content = delta?.optString("content", "") ?: ""
                                    if (content.isNotEmpty()) {
                                        fullResponse.append(content)
                                        withContext(Dispatchers.Main) {
                                            currentAiBubbleView?.text = fullResponse.toString()
                                            scrollToBottom()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                continue
                            }
                        }
                    }

                    val finalAnswer = fullResponse.toString()
                    chatHistory.add(Pair(prompt, finalAnswer))

                    withContext(Dispatchers.Main) {
                        speakOut(finalAnswer)
                    }

                } else {
                    withContext(Dispatchers.Main) {
                        val errorMsg = "서버 응답 오류가 발생했습니다. (코드: $responseCode)"
                        currentAiBubbleView?.text = errorMsg
                        if (isVoiceModeActive) {
                            speakOut("서버 응답 오류가 발생했습니다.")
                        }
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val errorMsg = "네트워크 연결 상태를 확인해주세요."
                    currentAiBubbleView?.text = errorMsg
                    if (isVoiceModeActive) {
                        speakOut("네트워크 연결 상태를 확인해주세요.")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        stopSpeechRecognizer()
        super.onDestroy()
    }
}
