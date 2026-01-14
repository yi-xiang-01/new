package com.example.mapcollection

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.example.mapcollection.network.AiVoiceReq
import com.example.mapcollection.network.ApiClient
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.util.Locale

class AiVoiceBotActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var btnBack: MaterialButton
    private lateinit var tvSpotTitle: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var btnMic: ImageButton
    private lateinit var lottie: LottieAnimationView

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    // 從 TripStopDetail 帶過來的上下文
    private var spotName: String? = null
    private var desc: String? = null
    private var startTime: String? = null
    private var endTime: String? = null
    private var lat: Double = Double.NaN
    private var lng: Double = Double.NaN

    companion object {
        private const val REQ_RECORD_AUDIO = 101
    }

    // ===== 機器人狀態 =====
    private enum class RobotState { IDLE, LISTENING, TALKING }
    private var robotState: RobotState = RobotState.IDLE

    private fun setRobotState(state: RobotState) {
        if (robotState == state) return
        robotState = state

        when (state) {
            RobotState.IDLE -> {
                // 沒有 idle.json 就先用 listening 當待機畫面
                lottie.setAnimation("listening.json")
                lottie.repeatCount = LottieDrawable.INFINITE
                lottie.pauseAnimation()
            }
            RobotState.LISTENING -> {
                lottie.setAnimation("listening.json")
                lottie.repeatCount = LottieDrawable.INFINITE
                lottie.playAnimation()
            }
            RobotState.TALKING -> {
                lottie.setAnimation("talking.json")
                lottie.repeatCount = LottieDrawable.INFINITE
                lottie.playAnimation()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_voice_bot)

        // ===== 取參數 =====
        spotName = intent.getStringExtra("SPOT_NAME")
        desc = intent.getStringExtra("DESC")
        startTime = intent.getStringExtra("START_TIME")
        endTime = intent.getStringExtra("END_TIME")
        lat = intent.getDoubleExtra("LAT", Double.NaN)
        lng = intent.getDoubleExtra("LNG", Double.NaN)

        // ===== bind views =====
        btnBack = findViewById(R.id.btnBack)
        tvSpotTitle = findViewById(R.id.tvSpotTitle)
        tvTranscript = findViewById(R.id.tvTranscript)
        btnMic = findViewById(R.id.btn_mic)
        lottie = findViewById(R.id.lottie_character_view)

        btnBack.setOnClickListener { finish() }

        tvSpotTitle.text = spotName?.takeIf { it.isNotBlank() } ?: "AI 導遊"
        tvTranscript.text = "（按下麥克風開始說話）"

        // ✅ 初始化 Lottie（不要用 XML 的 lottie_autoPlay / lottie_loop）
        lottie.repeatCount = LottieDrawable.INFINITE
        setRobotState(RobotState.IDLE)

        // ✅ 初始化 TTS
        tts = TextToSpeech(this, this)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread { setRobotState(RobotState.TALKING) }
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread { setRobotState(RobotState.IDLE) }
            }

            override fun onError(utteranceId: String?) {
                runOnUiThread { setRobotState(RobotState.IDLE) }
            }
        })

        btnMic.setOnClickListener { ensureMicPermissionAndStart() }
    }

    private fun ensureMicPermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_RECORD_AUDIO
            )
            return
        }
        startListening()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                Toast.makeText(this, "需要麥克風權限才能語音詢問", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "此裝置不支援語音辨識（模擬器常見）", Toast.LENGTH_LONG).show()
            return
        }

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        tvTranscript.text = "（請開始說話…）"
                        setRobotState(RobotState.LISTENING)
                    }

                    override fun onResults(results: Bundle?) {
                        setRobotState(RobotState.IDLE)

                        val texts =
                            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val userText = texts?.firstOrNull().orEmpty().trim()

                        if (userText.isBlank()) {
                            tvTranscript.text = "（沒有聽清楚，請再試一次）"
                            return
                        }

                        tvTranscript.text = "你：$userText"
                        askAiViaBackend(userText)
                    }

                    override fun onError(error: Int) {
                        setRobotState(RobotState.IDLE)
                        tvTranscript.text = "（語音辨識失敗，請再按一次麥克風）"
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "請說出你想問的問題")
        }

        speechRecognizer?.startListening(intent)
    }

    /** ✅ 前後端分離：手機只送文字 + 上下文，後端組 prompt / 呼叫 Gemini */
    private fun askAiViaBackend(userText: String) {
        lifecycleScope.launch {
            try {
                val req = AiVoiceReq(
                    text = userText,
                    spotName = spotName?.ifBlank { "未命名地點" } ?: "未命名地點",
                    desc = desc?.orEmpty() ?: "",
                    startTime = startTime?.orEmpty() ?: "",
                    endTime = endTime?.orEmpty() ?: "",
                    lat = if (lat.isNaN()) null else lat,
                    lng = if (lng.isNaN()) null else lng
                )

                val res = ApiClient.api.aiVoice(req)
                val answer = res.text.trim().ifBlank { "（AI 沒有回覆內容）" }

                tvTranscript.text = "${tvTranscript.text}\n\nAI：$answer"
                speak(answer)
            } catch (e: Exception) {
                Toast.makeText(
                    this@AiVoiceBotActivity,
                    "詢問失敗：${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun speak(text: String) {
        // 觸發 UtteranceProgressListener，開始說話會自動切到 TALKING 動畫
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ai_reply")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.TAIWAN
        } else {
            Toast.makeText(this, "TTS 初始化失敗", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
