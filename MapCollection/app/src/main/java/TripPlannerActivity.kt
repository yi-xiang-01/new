package com.example.mapcollection

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mapcollection.network.ApiClient
import com.example.mapcollection.network.TripStopRes
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

data class TripStop(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val lat: Double = .0,
    val lng: Double = .0,
    val photoUrl: String? = null,
    val startTime: String = "",
    val endTime: String = "",
    val aiSuggestion: String = "",
    val category: String = "景點"
)

class TripPlannerActivity : AppCompatActivity() {

    private var tripId: String? = null
    private var tripTitle: String? = null
    private var day: Int = 1
    private var myEmail: String? = null

    private lateinit var rv: RecyclerView
    private lateinit var adapter: TripStopAdapter
    private val stops = mutableListOf<TripStop>()

    private lateinit var tvDay: TextView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_planner)

        myEmail = getSharedPreferences("Account", MODE_PRIVATE)
            .getString("LOGGED_IN_EMAIL", null)

        tripId = intent.getStringExtra("TRIP_ID")
        tripTitle = intent.getStringExtra("TRIP_TITLE")
        day = intent.getIntExtra("DAY", 1).coerceIn(1, 7)

        if (tripId.isNullOrBlank() || myEmail.isNullOrBlank()) {
            finish()
            return
        }

        findViewById<TextView>(R.id.tvTripTitle).text = tripTitle ?: "我的行程"

        tvDay = findViewById(R.id.tvDay)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        updateDayText()

        btnPrev.setOnClickListener {
            if (day > 1) {
                day -= 1
                updateDayText()
                loadStops()
            }
        }
        btnNext.setOnClickListener {
            if (day < 7) {
                day += 1
                updateDayText()
                loadStops()
            }
        }

        rv = findViewById(R.id.rvDayStops)
        rv.layoutManager = LinearLayoutManager(this)

        adapter = TripStopAdapter(
            data = stops,
            onClick = { pos ->
                stops.getOrNull(pos)?.let { openStopDetail(it) }
            },
            onAskAi = { pos ->
                generateSuggestionIfNeeded(pos, force = true)
            },
            onDelete = { stop ->
                confirmAndDeleteStop(stop)
            }
        )
        rv.adapter = adapter

        findViewById<ImageButton>(R.id.btnAddStop).setOnClickListener {
            startActivity(
                Intent(this, PickLocationActivity::class.java)
                    .putExtra("TRIP_ID", tripId)
                    .putExtra("DAY", day)
            )
        }

        findViewById<ImageButton>(R.id.btnAddCollaborator).setOnClickListener {
            Toast.makeText(this, "加協作者功能（待接後端）", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        loadStops()
    }

    private fun updateDayText() {
        tvDay.text = "Day $day"
    }

    private fun loadStops() {
        val t = tripId ?: return
        val email = myEmail ?: return

        // 先清 UI（你原本習慣這樣也 OK）
        stops.clear()
        adapter.notifyDataSetChanged()

        lifecycleScope.launch {
            try {
                val list: List<TripStopRes> = ApiClient.api.getTripDayStops(
                    tripId = t,
                    day = day,
                    email = email
                )

                val mapped = list.map {
                    TripStop(
                        id = it.id,
                        name = it.name,
                        description = it.description,
                        lat = it.lat,
                        lng = it.lng,
                        photoUrl = it.photoUrl,
                        startTime = it.startTime,
                        endTime = it.endTime,
                        aiSuggestion = it.aiSuggestion,
                        category = it.category
                    )
                }.toMutableList()

                // ✅ 時間排序（有時間的在前，沒時間的在後）
                mapped.sortWith(
                    compareBy<TripStop> { timeKeyOrMax(it.startTime) }
                        .thenBy { timeKeyOrMax(it.endTime) }
                        .thenBy { it.name } // 同時間再按名稱，避免跳動
                )

                stops.clear()
                stops.addAll(mapped)
                adapter.notifyDataSetChanged()

                autoGenerateMissingSuggestions(limit = 3)
            } catch (e: Exception) {
                Toast.makeText(
                    this@TripPlannerActivity,
                    "載入行程點失敗：${e.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun autoGenerateMissingSuggestions(limit: Int = 3) {
        var count = 0
        for (i in stops.indices) {
            if (count >= limit) break
            if (stops[i].aiSuggestion.isBlank()) {
                count++
                generateSuggestionIfNeeded(i, force = false)
            }
        }
    }

    private fun openStopDetail(s: TripStop) {
        val t = tripId ?: return
        startActivity(
            Intent(this, TripStopDetailActivity::class.java)
                .putExtra("TRIP_ID", t)
                .putExtra("DAY", day)
                .putExtra("STOP_ID", s.id)
        )
    }

    private fun confirmAndDeleteStop(stop: TripStop) {
        val t = tripId ?: return
        val email = myEmail ?: return

        AlertDialog.Builder(this)
            .setTitle("刪除行程點")
            .setMessage("確定要刪除「${stop.name.ifBlank { "未命名景點" }}」嗎？")
            .setNegativeButton("取消", null)
            .setPositiveButton("刪除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        ApiClient.api.deleteTripStop(
                            tripId = t,
                            day = day,
                            stopId = stop.id,
                            email = email
                        )

                        val index = stops.indexOfFirst { it.id == stop.id }
                        if (index >= 0) {
                            stops.removeAt(index)
                            // 刪掉後再排序一次（保險）
                            stops.sortWith(
                                compareBy<TripStop> { timeKeyOrMax(it.startTime) }
                                    .thenBy { timeKeyOrMax(it.endTime) }
                                    .thenBy { it.name }
                            )
                            adapter.notifyDataSetChanged()
                        }

                        Toast.makeText(this@TripPlannerActivity, "已刪除景點", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@TripPlannerActivity,
                            "刪除失敗：${e.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun generateSuggestionIfNeeded(position: Int, force: Boolean) {
        val t = tripId ?: return
        val email = myEmail ?: return
        val s = stops.getOrNull(position) ?: return
        if (!force && s.aiSuggestion.isNotBlank()) return

        stops[position] = s.copy(aiSuggestion = "AI 建議生成中…")
        adapter.notifyItemChanged(position)

        val loading = AlertDialog.Builder(this)
            .setMessage("AI 生成中…")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val res = ApiClient.api.generateStopAi(
                    tripId = t,
                    day = day,
                    stopId = s.id,
                    email = email
                )
                loading.dismiss()

                val text = res.text.trim().ifBlank { "（暫時無法產生建議，請稍後再試）" }
                val now = stops.getOrNull(position)
                if (now != null) {
                    stops[position] = now.copy(aiSuggestion = text)
                    adapter.notifyItemChanged(position)
                }
            } catch (e: Exception) {
                loading.dismiss()

                Log.e("Gemini", "generateSuggestion failed", e)

                val msg = when (e) {
                    is HttpException -> {
                        val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                        "HTTP ${e.code()} ${e.message()} ${body ?: ""}"
                    }
                    is IOException -> "網路錯誤（請確認有開網路/模擬器可上網）：${e.message}"
                    else -> (e.message ?: e.toString())
                }

                Toast.makeText(this@TripPlannerActivity, "產生建議失敗：$msg", Toast.LENGTH_LONG).show()

                val cur = stops.getOrNull(position)
                if (cur != null) {
                    stops[position] = cur.copy(aiSuggestion = "")
                    adapter.notifyItemChanged(position)
                }
            }
        }
    }

    /**
     * 把 "HH:mm" 轉成可排序的分鐘數。
     * 空白/--:--/格式錯誤 → 排最後（Int.MAX_VALUE）
     */
    private fun timeKeyOrMax(hhmm: String?): Int {
        val t = (hhmm ?: "").trim()
        if (t.isBlank() || t == "--:--") return Int.MAX_VALUE

        val parts = t.split(":")
        if (parts.size != 2) return Int.MAX_VALUE

        val h = parts[0].toIntOrNull() ?: return Int.MAX_VALUE
        val m = parts[1].toIntOrNull() ?: return Int.MAX_VALUE
        if (h !in 0..23 || m !in 0..59) return Int.MAX_VALUE

        return h * 60 + m
    }
}
