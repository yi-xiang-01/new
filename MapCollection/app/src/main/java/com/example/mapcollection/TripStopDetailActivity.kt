package com.example.mapcollection

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.mapcollection.network.ApiClient
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.button.MaterialButton
import com.google.firebase.Firebase
import com.google.firebase.firestore.FieldValue
import com.example.mapcollection.network.UpdateTripStopReq
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import java.io.IOException

class TripStopDetailActivity : AppCompatActivity(), OnMapReadyCallback {

    private val db = Firebase.firestore

    private var tripId: String? = null
    private var day: Int = 1
    private var stopId: String? = null
    private var myEmail: String = ""

    // UI
    private lateinit var tvTitle: TextView
    private lateinit var tvDesc: TextView
    private lateinit var tvTimeRange: TextView
    private lateinit var btnEditTime: MaterialButton
    private lateinit var btnEditStop: MaterialButton
    private lateinit var btnAskAI: Button
    private lateinit var btnNavigate: Button
    private lateinit var ivPhoto: ImageView
    private lateinit var btnUploadPhoto: Button

    // Map
    private var gMap: GoogleMap? = null

    // stop data
    private var startTime: String = ""
    private var endTime: String = ""
    private var category: String = "景點"

    private var lat: Double = Double.NaN
    private var lng: Double = Double.NaN
    private var description: String = ""
    private var name: String = ""
    private var photoUrl: String? = null

    // 選圖
    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) uploadTripStopPhoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_stop_detail)

        myEmail = getSharedPreferences("Account", MODE_PRIVATE)
            .getString("LOGGED_IN_EMAIL", "")
            ?.trim()
            .orEmpty()

        tripId = intent.getStringExtra("TRIP_ID")
        day = intent.getIntExtra("DAY", 1)
        stopId = intent.getStringExtra("STOP_ID")

        if (tripId.isNullOrBlank() || stopId.isNullOrBlank() || myEmail.isBlank()) {
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
        ivPhoto = findViewById(R.id.ivPhoto)
        btnUploadPhoto = findViewById(R.id.btnUploadPhoto)

        btnEditTime.setOnClickListener { showEditTimeDialog() }
        btnEditStop.setOnClickListener { showEditStopDialog() }

        // ✅ 詢問AI → 跳語音機器人（帶上下文）
        btnAskAI.setOnClickListener { openAiVoiceBot() }

        // ✅ 導航
        btnNavigate.setOnClickListener {
            val label = tvTitle.text.toString().trim().ifBlank { "目的地" }
            openGoogleNavigation(lat, lng, label)
        }

        // ✅ 上傳照片：選圖 → 丟 Flask → 回 photoUrl → 顯示
        btnUploadPhoto.setOnClickListener {
            pickPhotoLauncher.launch("image/*")
        }

        // Map init
        val mapFragment =
            supportFragmentManager.findFragmentById(R.id.mapStop) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        loadStopFromFirestore()
    }

    override fun onMapReady(map: GoogleMap) {
        gMap = map
        refreshMapMarker()
    }

    private fun refreshMapMarker() {
        val map = gMap ?: return
        map.clear()

        if (lat.isNaN() || lng.isNaN()) return
        val p = LatLng(lat, lng)

        map.addMarker(MarkerOptions().position(p).title(name.ifBlank { "目的地" }))
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(p, 16f))
    }

    /** 仍用 Firestore 讀（讓畫面永遠跟 DB 一致） */
    private fun loadStopFromFirestore() {
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
                photoUrl = doc.getString("photoUrl")

                tvTitle.text = name.ifBlank { "未命名景點" }
                tvDesc.text = description.ifBlank { "（沒有描述）" }
                updateTimeUI()
                refreshMapMarker()

                if (!photoUrl.isNullOrBlank()) {
                    Glide.with(this).load(photoUrl).into(ivPhoto)
                } else {
                    ivPhoto.setImageResource(R.drawable.map)
                }
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

    /** ✅ 上傳行程點照片（丟 Flask） */
    private fun uploadTripStopPhoto(uri: Uri) {
        val t = tripId ?: return
        val sId = stopId ?: return

        if (myEmail.isBlank()) {
            Toast.makeText(this, "找不到登入 Email", Toast.LENGTH_SHORT).show()
            return
        }

        val loading = AlertDialog.Builder(this)
            .setMessage("上傳中...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val tmp = ByteArray(8 * 1024)
                    while (true) {
                        val r = input.read(tmp)
                        if (r <= 0) break
                        buffer.write(tmp, 0, r)
                    }
                    buffer.toByteArray()
                }

                if (bytes == null || bytes.isEmpty()) {
                    loading.dismiss()
                    Toast.makeText(this@TripStopDetailActivity, "讀取圖片失敗", Toast.LENGTH_SHORT)
                        .show()
                    return@launch
                }

                val emailBody = myEmail.toRequestBody("text/plain".toMediaTypeOrNull())
                val photoBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData(
                    name = "photo",
                    filename = "trip_stop.jpg",
                    body = photoBody
                )

                val res = ApiClient.api.uploadTripStopPhoto(
                    tripId = t,
                    day = day,
                    stopId = sId,
                    email = emailBody,
                    photo = part
                )

                loading.dismiss()
                Toast.makeText(this@TripStopDetailActivity, "上傳成功", Toast.LENGTH_SHORT).show()

                photoUrl = res.photoUrl
                Glide.with(this@TripStopDetailActivity).load(photoUrl).into(ivPhoto)

                // 保險：再拉一次 Firestore（確保一致）
                loadStopFromFirestore()

            } catch (e: Exception) {
                loading.dismiss()
                Toast.makeText(
                    this@TripStopDetailActivity,
                    "上傳失敗：${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** ===== 修改時間：改成呼叫後端 PUT（後端會自動刷新 AI）===== */
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

                // 先更新 UI
                startTime = newStart
                endTime = newEnd
                updateTimeUI()

                // ✅ 改成走 API（後端會判斷欄位變更→刷新 AI）
                saveStopFieldsViaApi(
                    mapOf(
                        "startTime" to newStart,
                        "endTime" to newEnd
                    )
                )

            }, eh, em, true).show()

        }, sh, sm, true).show()
    }

    /** ===== 修改行程：改成呼叫後端 PUT（後端會自動刷新 AI）===== */
    private fun showEditStopDialog() {
        val nameEt = EditText(this).apply {
            hint = "景點名稱"
            setText(tvTitle.text.toString())
        }
        val descEt = EditText(this).apply {
            hint = "描述 / 備註"
            setText(
                description.takeIf { it.isNotBlank() }
                ?: tvDesc.text.toString().takeIf { it != "（沒有描述）" }.orEmpty()
            )
        }

        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbSpot = RadioButton(this).apply { text = "景點" }
        val rbFood = RadioButton(this).apply { text = "用餐" }
        rg.addView(rbSpot)
        rg.addView(rbFood)

        if (category.contains("用餐")) rbFood.isChecked = true else rbSpot.isChecked = true

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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

                // 先更新 UI
                name = newName
                description = newDesc
                category = newCat

                tvTitle.text = newName.ifBlank { "未命名景點" }
                tvDesc.text = newDesc.ifBlank { "（沒有描述）" }
                refreshMapMarker()

                // ✅ 改成走 API（後端會刷新 AI）
                saveStopFieldsViaApi(
                    mapOf(
                        "name" to newName,
                        "description" to newDesc,
                        "category" to newCat
                    )
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * ✅ 核心：改成呼叫 Flask PUT
     * 後端會：
     * 1) set updates + updatedAt
     * 2) 如果更新欄位屬於 AI_AFFECT_FIELDS → 重算 AI → 回 aiSuggestion
     */
    private fun saveStopFieldsViaApi(fields: Map<String, Any>) {
        val t = tripId ?: return
        val sId = stopId ?: return

        val email = getSharedPreferences("Account", MODE_PRIVATE)
            .getString("LOGGED_IN_EMAIL", null)
            ?.trim()
            .orEmpty()

        if (email.isBlank()) {
            Toast.makeText(this, "找不到登入 Email", Toast.LENGTH_SHORT).show()
            return
        }

        // ✅ Map -> UpdateTripStopReq（只取你允許更新的欄位）
        val req = UpdateTripStopReq(
            name = fields["name"] as? String,
            description = fields["description"] as? String,
            category = fields["category"] as? String,
            startTime = fields["startTime"] as? String,
            endTime = fields["endTime"] as? String,
            lat = (fields["lat"] as? Number)?.toDouble(),
            lng = (fields["lng"] as? Number)?.toDouble(),
            openingHours = fields["openingHours"] // 可能是 Map/Any，照傳
        )

        val loading = AlertDialog.Builder(this)
            .setMessage("更新中...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val res = ApiClient.api.updateTripStop(
                    tripId = t,
                    day = day,
                    stopId = sId,
                    email = email,
                    req = req
                )
                loading.dismiss()

                when {
                    res.refreshed && !res.aiSuggestion.isNullOrBlank() -> {
                        Toast.makeText(
                            this@TripStopDetailActivity,
                            "已更新，AI 已刷新",
                            Toast.LENGTH_SHORT
                        ).show()
                        // 如果你有 AI 文字欄位：
                        // tvAi.text = res.aiSuggestion
                    }

                    !res.aiError.isNullOrBlank() -> {
                        Toast.makeText(
                            this@TripStopDetailActivity,
                            "已更新，但 AI 刷新失敗：${res.aiError}",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    else -> {
                        Toast.makeText(this@TripStopDetailActivity, "已更新", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                // ✅ 你現在 UI 主要還是用 Firestore 畫面 → 更新後再拉一次保險
                loadStopFromFirestore()

            } catch (e: Exception) {
                loading.dismiss()
                Toast.makeText(
                    this@TripStopDetailActivity,
                    "更新失敗：${e.localizedMessage ?: e}",
                    Toast.LENGTH_LONG
                ).show()

                // 失敗就重新拉資料回復 UI
                loadStopFromFirestore()
            }
        }
    }
}
