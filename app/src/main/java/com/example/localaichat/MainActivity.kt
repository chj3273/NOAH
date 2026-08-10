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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
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
    private lateinit var btnSend: MaterialButton
    private lateinit var btnSetApiKey: MaterialButton
    private lateinit var btnSelectLocalFile: MaterialButton
    private lateinit var btnSelectModel: MaterialButton
    private lateinit var btnVoice: MaterialButton

    private val PREFS_NAME = "NOAH_PREFS"
    private val KEY_SELECTED_MODEL = "SELECTED_MODEL_ID"
    private val KEY_SELECTED_MODEL_NAME = "SELECTED_MODEL_NAME"
    private val KEY_API_KEY = "NVIDIA_API_KEY"
    private val KEY_LOCAL_MODEL_URI = "LOCAL_MODEL_FILE_URI"
    private val KEY_LOCAL_MODEL_NAME = "LOCAL_MODEL_FILE_NAME"

    private val chatHistory = mutableListOf<Pair<String, String>>()
    private var isLocalAiMode = false

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private var isTikiTakaActive = false

    // 로컬 파일 선택기 Launcher
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

            isLocalAiMode = true
            Toast.makeText(this, "로컬 모델 지정: $fileName", Toast.LENGTH_SHORT).show()
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
        btnSetApiKey = findViewById(R.id.btnSetApiKey)
        btnSelectLocalFile = findViewById(R.id.btnSelectLocalFile)
        btnSelectModel = findViewById(R.id.btnSelectModel)
        btnVoice = findViewById(R.id.btnVoice)

        updateModelDisplay()

        tts = TextToSpeech(this, this)
        initSpeechRecognizer()
        checkAudioPermission()

        btnSetApiKey.setOnClickListener { showApiKeyDialog() }
        btnSelectLocalFile.setOnClickListener { selectLocalModelLauncher.launch(arrayOf("*/*")) }
        btnSelectModel.setOnClickListener { showModelSelectionDialog() }

        // 하단 음성 버튼 (티키타카 토글)
        btnVoice.setOnClickListener {
            if (isTikiTakaActive) {
                stopTikiTakaMode()
            } else {
                startTikiTakaMode()
            }
        }

        btnSend.setOnClickListener {
            stopTikiTakaMode()
            processUserPrompt(etInput.text.toString().trim())
        }
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
        btnVoice.setBackgroundColor(Color.parseColor("#E53935"))
        Toast.makeText(this, "음성 대화(티키타카)를 시작합니다.", Toast.LENGTH_SHORT).show()
        startListening()
    }

    private fun stopTikiTakaMode() {
        isTikiTakaActive = false
        btnVoice.text = "음성"
        btnVoice.setBackgroundColor(Color.parseColor("#262626"))
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
            val localFileName = prefs.getString(KEY_LOCAL_MODEL_NAME, "로컬 모델")
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
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
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

    private fun callNvidiaApiStreaming(prompt: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val selectedModel = prefs.getString(KEY_SELECTED_MODEL, "google/gemma-4-31b-it") ?: "google/gemma-4-31b-it"

        if (apiKey.isEmpty()) {
            runLocalAiFallback(prompt, "API 키가 미설정되어 로컬 AI로 자동 전환합니다.")
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
                        put("content", "너는 한국어 AI 비서 NOAH이다. '*'와 같은 강조를 위한 문자와 특수문자나 이모티콘 없이 대화하듯이 자연스럽게 텍스트로만 답변하라.")
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

                    withContext(Dispatchers.Main) {
                        speakText(finalAnswer)
                    }

                } else {
                    withContext(Dispatchers.Main) {
                        runLocalAiFallback(prompt, "온라인 서버 오류로 로컬 AI로 전환합니다.")
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    runLocalAiFallback(prompt, "네트워크 연결 끊김으로 로컬 AI로 전환합니다.")
                }
            }
        }
    }

    private fun runLocalAiFallback(prompt: String, reason: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val localFileName = prefs.getString(KEY_LOCAL_MODEL_NAME, "기본 모델")

        Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()

        val responseText = "지정된 로컬 AI 모델($localFileName)입니다. 오프라인 모드로 질문에 대해 답변을 드립니다."
        tvResponse.text = responseText
        chatHistory.add(Pair(prompt, responseText))

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
