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
data class ChatRoom(val id: Long, val title: String)

class ChatDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "noah_chat_rooms.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chat_rooms (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT)")
        db.execSQL("CREATE TABLE chat_history (id INTEGER PRIMARY KEY AUTOINCREMENT, room_id INTEGER, role TEXT, content TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS chat_history")
        db.execSQL("DROP TABLE IF EXISTS chat_rooms")
        onCreate(db)
    }

    fun createRoom(title: String): Long {
        val values = ContentValues().apply { put("title", title) }
        return writableDatabase.insert("chat_rooms", null, values)
    }

    fun updateRoomTitle(roomId: Long, newTitle: String) {
        val values = ContentValues().apply { put("title", newTitle) }
        writableDatabase.update("chat_rooms", values, "id = ?", arrayOf(roomId.toString()))
    }

    @SuppressLint("Range")
    fun getAllRooms(): List<ChatRoom> {
        val list = mutableListOf<ChatRoom>()
        val cursor = readableDatabase.rawQuery("SELECT * FROM chat_rooms ORDER BY id ASC", null)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(cursor.getColumnIndex("id"))
            val title = cursor.getString(cursor.getColumnIndex("title"))
            list.add(ChatRoom(id, title))
        }
        cursor.close()
        return list
    }

    fun deleteRoom(roomId: Long) {
        writableDatabase.delete("chat_history", "room_id = ?", arrayOf(roomId.toString()))
        writableDatabase.delete("chat_rooms", "id = ?", arrayOf(roomId.toString()))
    }

    fun insertMessage(roomId: Long, role: String, content: String): Long {
        val values = ContentValues().apply {
            put("room_id", roomId)
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
    fun getMessagesForRoom(roomId: Long): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        val cursor = readableDatabase.rawQuery("SELECT * FROM chat_history WHERE room_id = ? ORDER BY id ASC", arrayOf(roomId.toString()))
        while (cursor.moveToNext()) {
            val id = cursor.getLong(cursor.getColumnIndex("id"))
            val role = cursor.getString(cursor.getColumnIndex("role"))
            val content = cursor.getString(cursor.getColumnIndex("content"))
            list.add(ChatMessage(id, role, content))
        }
        cursor.close()
        return list
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
    private val KEY_API_KEY = "OPENROUTER_API_KEY"

    private lateinit var dbHelper: ChatDatabaseHelper
    private val messageHistory = mutableListOf<ChatMessage>()
    private var currentRoomId: Long = -1

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
        tvModelName.text = "NOAH"
        btnSelectModel.text = "채팅방"

        dbHelper = ChatDatabaseHelper(this)
        initDefaultRoom()

        tts = TextToSpeech(this, this)
        initSpeechRecognizer()
        checkAudioPermission()

        btnSetApiKey.setOnClickListener { showApiKeyDialog() }
        btnSelectModel.setOnClickListener { showChatRoomsDialog() }
        btnResetChat.setOnClickListener { clearCurrentRoom() }

        btnVoice.setOnClickListener {
            if (isTikiTakaActive) stopTikiTakaMode() else startTikiTakaMode()
        }

        btnSend.setOnClickListener {
            stopTikiTakaMode()
            processUserPrompt(etInput.text.toString().trim())
        }
    }

    private fun initDefaultRoom() {
        val rooms = dbHelper.getAllRooms()
        if (rooms.isEmpty()) {
            currentRoomId = dbHelper.createRoom("대화방 1")
        } else {
            currentRoomId = rooms[0].id
        }
        loadChatHistoryForCurrentRoom()
    }

    private fun loadChatHistoryForCurrentRoom() {
        chatLayout.removeAllViews()
        messageHistory.clear()
        val savedMessages = dbHelper.getMessagesForRoom(currentRoomId)
        for (msg in savedMessages) {
            messageHistory.add(msg)
            if (msg.content.isNotEmpty()) {
                addMessageView(msg.content, msg.role == "user")
            }
        }
    }

    private fun clearCurrentRoom() {
        chatLayout.removeAllViews()
        messageHistory.clear()
        val rooms = dbHelper.getAllRooms()
        val currentRoom = rooms.find { it.id == currentRoomId }
        val title = currentRoom?.title ?: "대화방"
        dbHelper.deleteRoom(currentRoomId)
        currentRoomId = dbHelper.createRoom(title)
        Toast.makeText(this, "현재 대화방이 초기화되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun showChatRoomsDialog() {
        val rooms = dbHelper.getAllRooms()
        val roomTitles = rooms.map { it.title }.toMutableList()
        roomTitles.add("+ 새 대화방 만들기")

        AlertDialog.Builder(this)
            .setTitle("채팅방 선택 및 관리")
            .setItems(roomTitles.toTypedArray()) { _, which ->
                if (which == rooms.size) {
                    val newRoomTitle = "대화방 ${rooms.size + 1}"
                    currentRoomId = dbHelper.createRoom(newRoomTitle)
                    loadChatHistoryForCurrentRoom()
                    Toast.makeText(this, "$newRoomTitle 생성됨", Toast.LENGTH_SHORT).show()
                } else {
                    val selectedRoom = rooms[which]
                    AlertDialog.Builder(this)
                        .setTitle(selectedRoom.title)
                        .setItems(arrayOf("대화방 열기", "대화방 이름 수정", "대화방 삭제")) { _, subWhich ->
                            when (subWhich) {
                                0 -> {
                                    currentRoomId = selectedRoom.id
                                    loadChatHistoryForCurrentRoom()
                                    Toast.makeText(this, "${selectedRoom.title} (으)로 이동", Toast.LENGTH_SHORT).show()
                                }
                                1 -> {
                                    showRenameRoomDialog(selectedRoom)
                                }
                                2 -> {
                                    if (rooms.size <= 1) {
                                        Toast.makeText(this, "마지막 대화방은 삭제할 수 없습니다.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        dbHelper.deleteRoom(selectedRoom.id)
                                        if (currentRoomId == selectedRoom.id) {
                                            val remainingRooms = dbHelper.getAllRooms()
                                            currentRoomId = remainingRooms[0].id
                                        }
                                        loadChatHistoryForCurrentRoom()
                                        Toast.makeText(this, "대화방이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        .show()
                }
            }
            .show()
    }

    private fun showRenameRoomDialog(room: ChatRoom) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 0)
        }

        val input = EditText(this).apply {
            setText(room.title)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#888888"))
            background = createRoundedDrawable("#2E2E2E", 8f)
            setPadding(32, 24, 32, 24)
        }

        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("대화방 이름 수정")
            .setView(container)
            .setPositiveButton("저장") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    dbHelper.updateRoomTitle(room.id, newTitle)
                    Toast.makeText(this, "대화방 이름이 변경되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
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

        val userId = dbHelper.insertMessage(currentRoomId, "user", prompt)
        messageHistory.add(ChatMessage(userId, "user", prompt))
        addMessageView(prompt, true)

        val aiTv = addMessageView("...", false)
        val aiId = dbHelper.insertMessage(currentRoomId, "assistant", "")
        val aiMsg = ChatMessage(aiId, "assistant", "")
        messageHistory.add(aiMsg)

        callOpenRouterApiStreaming(aiTv, aiMsg)
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

    private fun callOpenRouterApiStreaming(aiTv: TextView, aiMsg: ChatMessage) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val selectedModel = "openrouter/free"

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
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "너의 이름은 NOAH이다. 사용자의 질문에 답변할 때 별표나 마크다운 기호, 특수문자, 그리고 이모지나 이모티콘은 절대 사용하지 마라. 오직 순수 한국어 텍스트와 일반 문장 부호만 사용하여 자연스러운 구어체로 답하라.")
                    })

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
