package com.example.mapcollection

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.mapcollection.network.ApiClient
import com.example.mapcollection.network.AiAskReq
import kotlinx.coroutines.launch

class InformationActivity : AppCompatActivity() {

    private lateinit var tvLocName: TextView
    private lateinit var btnBack: Button
    private lateinit var btnAskAI: Button
    private lateinit var btnNearbySpots: Button

    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var spotName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.card_information)

        tvLocName = findViewById(R.id.tvLocName)
        btnBack = findViewById(R.id.btnBack)
        btnAskAI = findViewById(R.id.button)
        btnNearbySpots = findViewById(R.id.button2)

        latitude = intent.getDoubleExtra("latitude", 0.0)
        longitude = intent.getDoubleExtra("longitude", 0.0)
        spotName = intent.getStringExtra("spotName")
            ?: intent.getStringExtra("EXTRA_SPOT_NAME")

        val hasName = !spotName.isNullOrBlank() && spotName != "null"
        tvLocName.text = if (hasName) spotName else "座標: $latitude, $longitude"

        btnBack.setOnClickListener { finish() }
        btnAskAI.setOnClickListener { showAskAIDialog() }
        btnNearbySpots.setOnClickListener { findNearbyAttractions() }
    }

    private fun showAskAIDialog() {
        val editText = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("詢問AI")
            .setMessage("請輸入您想詢問關於這個地點的問題：")
            .setView(editText)
            .setPositiveButton("送出") { dialog, _ ->
                val question = editText.text.toString().trim()
                if (question.isNotBlank()) {
                    val prompt = "關於地點座標 ($latitude, $longitude)，我想知道：$question。請用繁體中文回答。"
                    callAI(prompt, "AI 的回答")
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun findNearbyAttractions() {
        val prompt =
            "請用繁體中文推薦在座標 ($latitude, $longitude) 附近的5個景點，並為每個景點提供一句話的簡短介紹。"
        callAI(prompt, "附近景點推薦")
    }

    private fun callAI(prompt: String, title: String) {
        val loadingDialog = AlertDialog.Builder(this)
            .setMessage("AI 思考中...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val res = ApiClient.api.aiAsk(AiAskReq(prompt = prompt))
                loadingDialog.dismiss()
                showResultDialog(title, res.text.ifBlank { "（AI 沒有回覆內容）" })
            } catch (e: Exception) {
                loadingDialog.dismiss()
                showResultDialog("錯誤", "發生錯誤：${e.localizedMessage}")
            }
        }
    }

    private fun showResultDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("關閉", null)
            .show()
    }
}
