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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private lateinit var etInput: EditText
    private lateinit var btnSend: MaterialButton
    private lateinit var btnSetApiKey: MaterialButton
    private lateinit var btnSelectLocalFile: MaterialButton
    private lateinit var btnSelectModel: MaterialButton
    private lateinit var btnResetChat: MaterialButton
    private lateinit var btnVoice: MaterialButton
    private lateinit var rvChat: RecyclerView

    private val PREFS_NAME = "NOAH_PREFS"
    private val KEY_SELECTED_MODEL = "SELECTED_MODEL_ID"
    private val KEY_SELECTED_MODEL_NAME = "SELECTED_MODEL_NAME"
    private val KEY_API_KEY = "NVIDIA_API_KEY"
    private val KEY_LOCAL_MODEL_NAME = "LOCAL_MODEL_FILE_NAME"
    private val KEY_IS_LOCAL_MODE = "IS_LOCAL_AI_MODE"

    private val messageList = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter

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
        rvChat = findViewById(R.id.rvChat)

        // 앱 시작 시 이전 설정 불러오기
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isLocalAiMode = prefs.getBoolean(KEY_IS_LOCAL_MODE, false)

        chatAdapter = ChatAdapter(messageList)
        rvChat.layoutManager = LinearLayoutManager(this)
        rvChat.adapter = chatAdapter

        // 초기 안내 메시지
        messageList.add(ChatMessage("안녕하세요. NOAH AI입니다. 질문을 입력하거나 음성 대화를 사용해 보세요.", false))
        chatAdapter.notifyItemInserted(0)

        updateModelDisplay()

        tts = TextToSpeech(this, this)
        initSpeechRecognizer()
        checkAudioPermission()

        btnSetApiKey.setOnClickListener { showApiKeyDialog() }
        btnSelectLocalFile.setOnClickListener { selectLocalModelLauncher.launch(arrayOf("*/*")) }
        btnSelectModel.setOnClickListener { showModelSelectionDialog() }
        btnResetChat.setOnClickListener { resetChatHistory() }

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

    private fun resetChatHistory() {
        messageList.clear()
        messageList.add(ChatMessage("대화 내역이 초기화되었습니다. 무엇을 도와드릴까요?", false))
        chatAdapter.notifyDataSetChanged()
        Toast.makeText(this, "채팅 초기화 완료", Toast.LENGTH_SHORT).show()
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
                    val recognizedText = matches[0]
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
        startListening()
    }

    private fun stopTikiTakaMode() {
        isTikiTakaActive = false
        btnVoice.text = "음성"
        btnVoice.setBackgroundColor(Color.parseColor("#222222"))
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

        // 1. 유저 메시지 추가
        messageList.add(ChatMessage(prompt, true))
        chatAdapter.notifyItemInserted(messageList.size - 1)

        // 2. AI 대기 메시지 추가
        messageList.add(ChatMessage("생성 중...", false))
        val aiIndex = messageList.size - 1
        chatAdapter.notifyItemInserted(aiIndex)
        rvChat.scrollToPosition(aiIndex)

        if (isLocalAiMode) {
            runLocalAiFallback(prompt, aiIndex, "로컬 AI 모드 실행")
        } else {
            callNvidiaApiStreaming(prompt, aiIndex)
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
        val savedKey = prefs.getString(KEY_API_KEY, "")

        val input = EditText(this).apply {
            setText(savedKey)
            hint = "nvapi-..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        AlertDialog.Builder(this)
            .setTitle("NVIDIA API Key")
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

    private fun callNvidiaApiStreaming(prompt: String, aiIndex: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val selectedModel = prefs.getString(KEY_SELECTED_MODEL, "google/gemma-4-31b-it") ?: "google/gemma-4-31b-it"

        if (apiKey.isEmpty()) {
            runLocalAiFallback(prompt, aiIndex, "API 키가 없습니다. API Key를 설정해 주세요.")
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
                    connectTimeout = 60000 // 타임아웃 1분으로 연장
                    readTimeout = 60000
                    doOutput = true
                }

                val messagesArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "너는 한국어 AI 비서 NOAH이다. '*'과 같이 텍스트를 강조하기 위한 문자나 특수문자나 이모티콘 없이 자연스러운 텍스트로만 대답하라.")
                    })
                    // 대화 내역 최근 30개 전달 (문맥 유지력 향상)
                    val history = messageList.filter { it.message != "생성 중..." }.takeLast(30)
                    for (msg in history) {
                        put(JSONObject().apply {
                            put("role", if (msg.isUser) "user" else "assistant")
                            put("content", msg.message)
                        })
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

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                    var line: String?
                    val fullResponse = StringBuilder()

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
                                            messageList[aiIndex].message = fullResponse.toString()
                                            chatAdapter.notifyItemChanged(aiIndex)
                                            rvChat.scrollToPosition(aiIndex)
                                        }
                                    }
                                }
                            } catch (e: Exception) { continue }
                        }
                    }

                    val finalAnswer = fullResponse.toString()
                    withContext(Dispatchers.Main) {
                        speakText(finalAnswer)
                    }

                } else {
                    withContext(Dispatchers.Main) {
                        runLocalAiFallback(prompt, aiIndex, "API 오류 Code: $responseCode")
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    runLocalAiFallback(prompt, aiIndex, "네트워크 오류: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun runLocalAiFallback(prompt: String, aiIndex: Int, reason: String) {
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()

        val responseText = "로컬 AI 모드 답변입니다. ($reason)"
        messageList[aiIndex].message = responseText
        chatAdapter.notifyItemChanged(aiIndex)

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
