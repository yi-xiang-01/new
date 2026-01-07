package com.example.mapcollection

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mapcollection.network.*
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ====== 畫面 ======
class PathActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var adapter: TripAdapter
    private val trips = mutableListOf<TripRes>()
    private var myEmail: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_path)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        myEmail = getSharedPreferences("Account", MODE_PRIVATE)
            .getString("LOGGED_IN_EMAIL", null)

        if (myEmail.isNullOrBlank()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        rv = findViewById(R.id.rvTrips)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = TripAdapter(
            data = trips,
            onClick = { pos -> openTrip(trips[pos]) },
            onMore = { pos, anchor -> showTripMenu(trips[pos], anchor) }
        )
        rv.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabNewTrip).setOnClickListener { promptNewTrip() }

        setupBottomNav()
    }

    override fun onResume() {
        super.onResume()
        loadTripsViaBackend()
    }

    private fun setupBottomNav() {
        findViewById<ImageButton>(R.id.btnRecommend).setOnClickListener {
            startActivity(Intent(this, RecommendActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnPath).setOnClickListener { /* stay */ }
        findViewById<ImageButton>(R.id.btnProfile).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    private fun loadTripsViaBackend() {
        val email = myEmail ?: return
        lifecycleScope.launch {
            try {
                val list = ApiClient.api.getMyTrips(email)
                trips.clear()
                trips.addAll(list)
                adapter.notifyDataSetChanged()
            } catch (e: Exception) {
                Snackbar.make(rv, "載入行程失敗：${e.localizedMessage}", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    /** 新增：日期區間（最長 7 天）+ 行程名稱 */
    private fun promptNewTrip() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("選擇行程日期（最長 7 天）")
            .build()

        picker.addOnPositiveButtonClickListener { sel ->
            val start = sel.first ?: return@addOnPositiveButtonClickListener
            var end = sel.second ?: start
            val maxEnd = start + 6L * 86_400_000L
            if (end > maxEnd) end = maxEnd

            val input = EditText(this).apply {
                hint = "行程名稱"
                inputType = InputType.TYPE_CLASS_TEXT
            }
            AlertDialog.Builder(this)
                .setTitle("建立新行程")
                .setView(input)
                .setPositiveButton("建立") { _, _ ->
                    val title = input.text?.toString()?.ifBlank { "我的行程" } ?: "我的行程"
                    createTripViaBackend(title, start, end)
                }
                .setNegativeButton("取消", null)
                .show()
        }
        picker.show(supportFragmentManager, "range")
    }

    private fun createTripViaBackend(title: String, startMillis: Long, endMillis: Long) {
        val email = myEmail ?: return
        lifecycleScope.launch {
            try {
                ApiClient.api.createTrip(
                    CreateTripReq(
                        email = email,
                        title = title,
                        startMillis = startMillis,
                        endMillis = endMillis
                    )
                )
                loadTripsViaBackend()
                rv.scrollToPosition(0)
            } catch (e: Exception) {
                Snackbar.make(rv, "建立失敗：${e.localizedMessage}", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    /** 右上角點點點選單 */
    private fun showTripMenu(t: TripRes, anchor: android.view.View) {
        val pm = PopupMenu(this, anchor, Gravity.END)
        val isOwner = t.ownerEmail == myEmail

        pm.menu.add(0, 1, 0, "重新命名")
        if (isOwner) pm.menu.add(0, 2, 1, "刪除")
        if (isOwner) pm.menu.add(0, 3, 2, "調整日期（最多 7 天）")

        pm.setOnMenuItemClickListener { mi: MenuItem ->
            when (mi.itemId) {
                1 -> renameTripViaBackend(t)
                2 -> if (isOwner) deleteTripViaBackend(t)
                3 -> if (isOwner) changeTripDatesViaBackend(t)
            }
            true
        }
        pm.show()
    }

    private fun renameTripViaBackend(t: TripRes) {
        val input = EditText(this).apply { setText(t.title) }
        AlertDialog.Builder(this)
            .setTitle("重新命名")
            .setView(input)
            .setPositiveButton("儲存") { _, _ ->
                val newTitle = input.text?.toString()?.ifBlank { "我的行程" } ?: "我的行程"
                val email = myEmail ?: return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        ApiClient.api.renameTrip(t.id, RenameTripReq(email, newTitle))
                        loadTripsViaBackend()
                    } catch (e: Exception) {
                        Snackbar.make(rv, "更新失敗：${e.localizedMessage}", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun changeTripDatesViaBackend(t: TripRes) {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("選擇新日期（最長 7 天）")
            .build()

        picker.addOnPositiveButtonClickListener { sel ->
            val start = sel.first ?: return@addOnPositiveButtonClickListener
            var end = sel.second ?: start
            val maxEnd = start + 6L * 86_400_000L
            if (end > maxEnd) end = maxEnd

            val email = myEmail ?: return@addOnPositiveButtonClickListener
            lifecycleScope.launch {
                try {
                    ApiClient.api.changeTripDates(
                        t.id,
                        ChangeTripDatesReq(email, start, end)
                    )
                    loadTripsViaBackend()
                } catch (e: Exception) {
                    Snackbar.make(rv, "更新失敗：${e.localizedMessage}", Snackbar.LENGTH_SHORT).show()
                }
            }
        }
        picker.show(supportFragmentManager, "range_edit")
    }

    private fun deleteTripViaBackend(t: TripRes) {
        val email = myEmail ?: return
        AlertDialog.Builder(this)
            .setTitle("刪除行程")
            .setMessage("確定要刪除「${t.title}」？")
            .setPositiveButton("刪除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        ApiClient.api.deleteTrip(t.id, email)
                        loadTripsViaBackend()
                    } catch (e: Exception) {
                        Snackbar.make(rv, "刪除失敗：${e.localizedMessage}", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openTrip(t: TripRes) {
        startActivity(
            Intent(this, TripPlannerActivity::class.java)
                .putExtra("TRIP_ID", t.id)
                .putExtra("TRIP_TITLE", t.title)
        )
    }
}

// ====== Adapter / ViewHolder ======
class TripAdapter(
    private val data: List<TripRes>,
    private val onClick: (Int) -> Unit,
    private val onMore: (Int, android.view.View) -> Unit
) : RecyclerView.Adapter<TripVH>() {

    private val fmt = SimpleDateFormat("MM/dd", Locale.TAIWAN)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): TripVH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trip_card, parent, false)
        return TripVH(v)
    }

    override fun onBindViewHolder(holder: TripVH, position: Int) {
        val t = data[position]
        holder.title.text = t.title

        val ctx = holder.itemView.context
        val me = ctx.getSharedPreferences("Account", AppCompatActivity.MODE_PRIVATE)
            .getString("LOGGED_IN_EMAIL", null)

        val ownerText = if (t.ownerEmail == me) "我" else t.ownerEmail

        val dateText = if (t.startDateMillis != null && t.endDateMillis != null) {
            "${fmt.format(Date(t.startDateMillis))} - ${fmt.format(Date(t.endDateMillis))}"
        } else "未設定"

        holder.meta.text = "擁有者：$ownerText • 協作者：${t.collaborators.size} 人 • $dateText"

        holder.itemView.setOnClickListener { onClick(position) }
        holder.more.setOnClickListener { onMore(position, holder.more) }
    }

    override fun getItemCount(): Int = data.size
}

class TripVH(v: android.view.View) : RecyclerView.ViewHolder(v) {
    val title: android.widget.TextView = v.findViewById(R.id.tvTitle)
    val meta: android.widget.TextView = v.findViewById(R.id.tvMeta)
    val more: android.widget.ImageButton = v.findViewById(R.id.btnMore)
}
