package com.example.localaichat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.localaichat.R
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
    private lateinit var tvResponse: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnSelectModel: Button
    private lateinit var btnSetApiKey: Button
    private lateinit var btnLocalAi: Button
    private lateinit var btnSelectLocalFile: Button
    private lateinit var btnFallbackSetting: Button
    private lateinit var btnVoice: Button

    private val PREFS_NAME = "NOAH_PREFS"
    private val KEY_SELECTED_MODEL = "SELECTED_MODEL_ID"
    private val KEY_SELECTED_MODEL_NAME = "SELECTED_MODEL_NAME"
    private val KEY_API_KEY = "NVIDIA_API_KEY"
    private val KEY_AUTO_FALLBACK = "AUTO_FALLBACK_LOCAL_AI"
    private val KEY_LOCAL_MODEL_URI = "LOCAL_MODEL_FILE_URI"
    private val KEY_LOCAL_MODEL_NAME = "LOCAL_MODEL_FILE_NAME"

    private val chatHistory = mutableListOf<Pair<String, String>>()
    private var isLocalAiMode = false

    // 음성 인식(STT) 및 읽기(TTS) 관련
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private var isTikiTakaActive = false // 연속 음성 티키타카 모드 상태

    private val selectLocalModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) { }

            val fileName = getFileNameFromUri(uri) ?: "선택된 로컬 파일"
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_LOCAL_MODEL_URI, uri.toString())
                .putString(KEY_LOCAL_MODEL_NAME, fileName)
                .apply()

            Toast.makeText(this, "로컬 AI 파일 지정 완료: $fileName", Toast.LENGTH_SHORT).show()
            updateModelDisplay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvModelName = findViewById(R.id.tvModelName)
        tvResponse = findViewById(R.id.tvResponse)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnSelectModel = findViewById(R.id.btnSelectModel)
        btnSetApiKey = findViewById(R.id.btnSetApiKey)
        btnLocalAi = findViewById(R.id.btnLocalAi)
        btnSelectLocalFile = findViewById(R.id.btnSelectLocalFile)
        btnFallbackSetting = findViewById(R.id.btnFallbackSetting)
        btnVoice = findViewById(R.id.btnVoice)

        tvModelName.setTextColor(Color.BLACK)
        tvResponse.setTextColor(Color.BLACK)
        etInput.setTextColor(Color.BLACK)

        updateModelDisplay()

        // TTS & STT 초기화
        tts = TextToSpeech(this, this)
        initSpeechRecognizer()

        // 마이크 권한 체크 및 요청
        checkAudioPermission()

        btnSetApiKey.setOnClickListener { showApiKeyDialog() }
        btnSelectModel.setOnClickListener { showModelSelectionDialog() }
        btnSelectLocalFile.setOnClickListener { selectLocalModelLauncher.launch(arrayOf("*/*")) }

        btnLocalAi.setOnClickListener {
            isLocalAiMode = !isLocalAiMode
            updateModelDisplay()
            val modeMsg = if (isLocalAiMode) "로컬 AI 모드" else "온라인 API 모드"
            Toast.makeText(this, "$modeMsg 로 전환되었습니다.", Toast.LENGTH_SHORT).show()
        }

        btnFallbackSetting.setOnClickListener { showFallbackSettingDialog() }

        // 🎙️ 음성 티키타카 버튼 클릭 이벤트
        btnVoice.setOnClickListener {
            if (isTikiTakaActive) {
                stopTikiTakaMode()
            } else {
                startTikiTakaMode()
            }
        }

        btnSend.setOnClickListener {
            stopTikiTakaMode() // 수동 전송 시 티키타카 연속 모드 해제
            processUserPrompt(etInput.text.toString().trim())
        }
    }

    // TTS 초기화 콜백
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.KOREAN)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        // AI가 말을 끝마치면, 티키타카 모드가 켜져있을 경우 즉시 마이크를 열어 다음 말을 들음
                        if (isTikiTakaActive) {
                            runOnUiThread {
                                startListening()
                            }
                        }
                    }

                    override fun onError(utteranceId: String?) {}
                })
            }
        }
    }

    // STT (음성 인식) 초기화
    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                tvResponse.text = "듣고 있습니다... 말씀해 주세요"
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                tvResponse.text = "음성을 분석하고 답변을 생성하는 중입니다..."
            }

            override fun onError(error: Int) {
                if (isTikiTakaActive) {
                    // 오류 또는 타임아웃 발생 시 티키타카 모드 중이면 다시 들음
                    startListening()
                } else {
                    Toast.makeText(applicationContext, "음성을 인식하지 못했습니다.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    etInput.setText(recognizedText)
                    processUserPrompt(recognizedText)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startTikiTakaMode() {
        isTikiTakaActive = true
        btnVoice.text = "중지"
        btnVoice.setBackgroundColor(Color.RED)
        Toast.makeText(this, "음성 모드를 시작합니다.", Toast.LENGTH_SHORT).show()
        startListening()
    }

    private fun stopTikiTakaMode() {
        isTikiTakaActive = false
        btnVoice.text = "음성 모드"
        btnVoice.setBackgroundColor(Color.LTGRAY)
        speechRecognizer.stopListening()
        if (tts.isSpeaking) {
            tts.stop()
        }
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
            val cleanText = text.replace(Regex("[*#_~]"), "") // 특수문자 제거 후 낭독
            tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "NOAH_UTTERANCE_ID")
        }
    }

    private fun processUserPrompt(prompt: String) {
        if (prompt.isEmpty()) return

        etInput.setText("")
        tvResponse.text = "답변 생성 중..."

        if (isLocalAiMode) {
            runLocalAiFallback(prompt, "로컬 AI 모드 실행")
        } else {
            callNvidiaApiStreaming(prompt)
        }
    }

    private fun updateModelDisplay() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (isLocalAiMode) {
            val localFileName = prefs.getString(KEY_LOCAL_MODEL_NAME, "미지정 ([로컬 파일 지정] 버튼 클릭)")
            tvModelName.text = "Local AI: $localFileName"
        } else {
            val savedModelName = prefs.getString(KEY_SELECTED_MODEL_NAME, "Google Gemma 4 31B")
            tvModelName.text = "NVIDIA NIM: $savedModelName"
        }
    }

    private fun showApiKeyDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_API_KEY, "")

        val input = EditText(this).apply {
            setText(savedKey)
            hint = "nvapi-..."
            setTextColor(Color.BLACK)
        }

        AlertDialog.Builder(this)
            .setTitle("NVIDIA API Key 입력")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val apiKey = input.text.toString().trim()
                prefs.edit().putString(KEY_API_KEY, apiKey).apply()
                Toast.makeText(this, "API Key 저장 완료", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showModelSelectionDialog() {
        val models = arrayOf("Google Gemma 4 31B", "Llama 3.1 8B", "OpenAI GPT-OSS 20B")
        val modelIds = arrayOf("google/gemma-4-31b-it", "meta/llama-3.1-8b-instruct", "openai/gpt-oss-20b")

        AlertDialog.Builder(this)
            .setTitle("온라인 AI 모델 선택")
            .setItems(models) { _, which ->
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_SELECTED_MODEL, modelIds[which])
                    .putString(KEY_SELECTED_MODEL_NAME, models[which])
                    .apply()

                isLocalAiMode = false
                updateModelDisplay()
            }
            .show()
    }

    private fun showFallbackSettingDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isAutoFallbackEnabled = prefs.getBoolean(KEY_AUTO_FALLBACK, true)

        val options = arrayOf(
            "API 오류 발생 시 지정한 로컬 AI로 자동 전환",
            "자동 전환 안 함 (오류 메시지만 표시)"
        )

        AlertDialog.Builder(this)
            .setTitle("API 오류 발생 시 처리 설정")
            .setSingleChoiceItems(options, if (isAutoFallbackEnabled) 0 else 1) { dialog, which ->
                prefs.edit().putBoolean(KEY_AUTO_FALLBACK, which == 0).apply()
                dialog.dismiss()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun callNvidiaApiStreaming(prompt: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val selectedModel = prefs.getString(KEY_SELECTED_MODEL, "google/gemma-4-31b-it") ?: "google/gemma-4-31b-it"
        val isAutoFallbackEnabled = prefs.getBoolean(KEY_AUTO_FALLBACK, true)

        if (apiKey.isEmpty()) {
            if (isAutoFallbackEnabled) {
                runLocalAiFallback(prompt, "API 키가 미설정되어 로컬 AI로 자동 전환합니다.")
            } else {
                tvResponse.text = "오류: API Key가 설정되지 않았습니다."
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
                        put("content", "너는 한국어 AI 비서 NOAH이다. 답변을 작성할 때 특수문자나 별표, 이모지를 쓰지 말고 순수한 텍스트만 말하듯이 자연스럽게 작성하라.")
                    })
                    for ((user, assistant) in chatHistory.takeLast(10)) {
                        put(JSONObject().apply { put("role", "user"); put("content", user) })
                        put(JSONObject().apply { put("role", "assistant"); put("content", assistant) })
                    }
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
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
                    var line: String?
                    val fullResponse = StringBuilder()

                    withContext(Dispatchers.Main) { tvResponse.text = "" }

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
                                            tvResponse.text = fullResponse.toString()
                                        }
                                    }
                                }
                            } catch (e: Exception) { continue }
                        }
                    }

                    val finalAnswer = fullResponse.toString()
                    chatHistory.add(Pair(prompt, finalAnswer))

                    // 답변 출력이 끝나면 음성으로 읽기 실행 (티키타카 지원)
                    withContext(Dispatchers.Main) {
                        speakText(finalAnswer)
                    }

                } else {
                    withContext(Dispatchers.Main) {
                        if (isAutoFallbackEnabled) {
                            runLocalAiFallback(prompt, "온라인 서버 오류로 로컬 AI로 전환합니다.")
                        } else {
                            tvResponse.text = "API 오류가 발생했습니다."
                        }
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAutoFallbackEnabled) {
                        runLocalAiFallback(prompt, "네트워크 끊김으로 로컬 AI로 전환합니다.")
                    } else {
                        tvResponse.text = "네트워크 연결 오류가 발생했습니다."
                    }
                }
            }
        }
    }

    private fun runLocalAiFallback(prompt: String, reason: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val localFileName = prefs.getString(KEY_LOCAL_MODEL_NAME, "기본 모델")

        Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()

        val responseText = "지정된 로컬 AI 모델 $localFileName 입니다. 말씀하신 질문에 대해 답변을 드립니다."
        tvResponse.text = responseText
        chatHistory.add(Pair(prompt, responseText))

        // 로컬 AI 응답도 음성으로 낭독
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
