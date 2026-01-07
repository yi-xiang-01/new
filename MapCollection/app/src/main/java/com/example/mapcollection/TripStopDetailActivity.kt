package com.example.mapcollection

import android.app.TimePickerDialog
import android.content.Intent
import android.widget.Button
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.firebase.Firebase
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.firestore

class TripStopDetailActivity : AppCompatActivity() {

    private val db = Firebase.firestore

    private var tripId: String? = null
    private var day: Int = 1
    private var stopId: String? = null

    // UI
    private lateinit var tvTitle: TextView
    private lateinit var tvDesc: TextView
    private lateinit var tvTimeRange: TextView
    private lateinit var btnEditTime: MaterialButton
    private lateinit var btnEditStop: MaterialButton

    private lateinit var btnAskAI: Button      // 你的 XML 底部那顆 btnAskAI
    private lateinit var btnNavigate: Button   // 你的 XML 底部那顆 btnNavigate

    // stop data
    private var startTime: String = ""
    private var endTime: String = ""
    private var category: String = "景點"

    private var lat: Double = Double.NaN
    private var lng: Double = Double.NaN
    private var description: String = ""
    private var name: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_stop_detail)

        // intent
        tripId = intent.getStringExtra("TRIP_ID")
        day = intent.getIntExtra("DAY", 1)
        stopId = intent.getStringExtra("STOP_ID")

        if (tripId == null || stopId == null) {
            finish()
            return
        }

        // Back
        findViewById<TextView?>(R.id.btnBack)?.setOnClickListener { finish() }

        // bind views
        tvTitle = findViewById(R.id.tvTitle)
        tvDesc = findViewById(R.id.tvDesc)
        tvTimeRange = findViewById(R.id.tvTimeRange)
        btnEditTime = findViewById(R.id.btnEditTime)
        btnEditStop = findViewById(R.id.btnEditStop)

        btnAskAI = findViewById(R.id.btnAskAI)
        btnNavigate = findViewById(R.id.btnNavigate)

        btnEditTime.setOnClickListener { showEditTimeDialog() }
        btnEditStop.setOnClickListener { showEditStopDialog() }

        // ✅ 詢問AI → 跳語音機器人（帶上下文）
        btnAskAI.setOnClickListener { openAiVoiceBot() }

        // ✅ 導航 → 開 Google Maps 導航到此點
        btnNavigate.setOnClickListener {
            val label = tvTitle.text.toString().trim().ifBlank { "目的地" }
            openGoogleNavigation(lat, lng, label)
        }

        loadStop()
    }

    private fun loadStop() {
        val t = tripId ?: return
        val sId = stopId ?: return

        db.collection("trips").document(t)
            .collection("days").document(day.toString())
            .collection("stops").document(sId)
            .get()
            .addOnSuccessListener { doc ->
                name = doc.getString("name").orEmpty()
                description = doc.getString("description").orEmpty()

                startTime = doc.getString("startTime").orEmpty()
                endTime = doc.getString("endTime").orEmpty()
                category = doc.getString("category") ?: "景點"

                lat = doc.getDouble("lat") ?: Double.NaN
                lng = doc.getDouble("lng") ?: Double.NaN

                tvTitle.text = name.ifBlank { "未命名景點" }
                tvDesc.text = description.ifBlank { "（沒有描述）" }
                updateTimeUI()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "載入失敗：${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateTimeUI() {
        val s = startTime.ifBlank { "--:--" }
        val e = endTime.ifBlank { "--:--" }
        tvTimeRange.text = "時間：$s - $e"
    }

    /** ✅ 跳到語音機器人 */
    private fun openAiVoiceBot() {
        val spotName = tvTitle.text.toString().trim().ifBlank { "未命名地點" }
        val desc = description.takeIf { it.isNotBlank() } ?: tvDesc.text.toString()

        val i = Intent(this, AiVoiceBotActivity::class.java)
            .putExtra("SPOT_NAME", spotName)
            .putExtra("DESC", desc)
            .putExtra("START_TIME", startTime)
            .putExtra("END_TIME", endTime)
            .putExtra("LAT", lat)
            .putExtra("LNG", lng)

        startActivity(i)
    }

    /** ✅ 開 Google Maps 導航 */
    private fun openGoogleNavigation(lat: Double, lng: Double, label: String) {
        if (lat.isNaN() || lng.isNaN()) {
            Toast.makeText(this, "沒有座標，無法導航", Toast.LENGTH_SHORT).show()
            return
        }

        val gmmIntentUri = Uri.parse("google.navigation:q=$lat,$lng&mode=d")
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
            setPackage("com.google.android.apps.maps")
        }

        if (mapIntent.resolveActivity(packageManager) != null) {
            startActivity(mapIntent)
        } else {
            val geoUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})")
            startActivity(Intent(Intent.ACTION_VIEW, geoUri))
        }
    }

    /** ===== 修改時間 ===== */
    private fun showEditTimeDialog() {
        fun parseHHmm(t: String): Pair<Int, Int> {
            val parts = t.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 12
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return h.coerceIn(0, 23) to m.coerceIn(0, 59)
        }

        val (sh, sm) = parseHHmm(startTime.ifBlank { "12:00" })
        val (eh, em) = parseHHmm(endTime.ifBlank { "13:00" })

        TimePickerDialog(this, { _, h, m ->
            val newStart = String.format("%02d:%02d", h, m)

            TimePickerDialog(this, { _, h2, m2 ->
                val newEnd = String.format("%02d:%02d", h2, m2)

                startTime = newStart
                endTime = newEnd
                updateTimeUI()

                saveStopFields(
                    mapOf(
                        "startTime" to startTime,
                        "endTime" to endTime,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }, eh, em, true).show()

        }, sh, sm, true).show()
    }

    /** ===== 修改行程（名稱/描述/類型）===== */
    private fun showEditStopDialog() {
        val nameEt = EditText(this).apply {
            hint = "景點名稱"
            setText(tvTitle.text.toString())
        }
        val descEt = EditText(this).apply {
            hint = "描述 / 備註"
            setText(description.takeIf { it.isNotBlank() }
                ?: tvDesc.text.toString().takeIf { it != "（沒有描述）" }.orEmpty()
            )
        }

        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbSpot = RadioButton(this).apply { text = "景點" }
        val rbFood = RadioButton(this).apply { text = "用餐" }
        rg.addView(rbSpot)
        rg.addView(rbFood)

        if (category.contains("用餐")) rbFood.isChecked = true else rbSpot.isChecked = true

        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
            addView(nameEt)
            addView(descEt)
            addView(rg)
        }

        AlertDialog.Builder(this)
            .setTitle("修改行程")
            .setView(box)
            .setPositiveButton("儲存") { _, _ ->
                val newName = nameEt.text.toString().trim()
                val newDesc = descEt.text.toString().trim()
                val newCat = if (rbFood.isChecked) "用餐" else "景點"

                name = newName
                description = newDesc
                category = newCat

                tvTitle.text = newName.ifBlank { "未命名景點" }
                tvDesc.text = newDesc.ifBlank { "（沒有描述）" }

                saveStopFields(
                    mapOf(
                        "name" to newName,
                        "description" to newDesc,
                        "category" to newCat,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** ===== 共用：寫回 Firestore ===== */
    private fun saveStopFields(fields: Map<String, Any>) {
        val t = tripId ?: return
        val sId = stopId ?: return

        db.collection("trips").document(t)
            .collection("days").document(day.toString())
            .collection("stops").document(sId)
            .update(fields)
            .addOnSuccessListener {
                Toast.makeText(this, "已更新", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "更新失敗：${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }
}
