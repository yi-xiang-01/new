package com.example.mapcollection

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mapcollection.network.ApiClient
import com.example.mapcollection.network.SpotRes
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

/** 前端顯示用 */
data class SpotUI(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val lat: Double = .0,
    val lng: Double = .0,
    val photoUrl: String? = null
)

class SpotListActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: SpotAdapter
    private val spots = mutableListOf<SpotUI>()

    private var postId: String? = null
    private var myEmail: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_spot_list)

        postId = intent.getStringExtra("POST_ID")
        myEmail = getSharedPreferences("Account", MODE_PRIVATE)
            .getString("LOGGED_IN_EMAIL", null)

        recycler = findViewById(R.id.rvSpots)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = SpotAdapter(spots, onDelete = { pos -> deleteSpotViaBackend(pos) })
        recycler.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabAddSpot).setOnClickListener {
            // 你現在的 NewPointActivity 是「回傳資料」模式（不直接打後端）
            // 所以這裡維持原樣：新增完回來 onResume 會重新載入
            startActivity(
                Intent(this, NewPointActivity::class.java)
                    .putExtra("POST_ID", postId)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        loadSpotsViaBackend()
    }

    private fun loadSpotsViaBackend() {
        val id = postId ?: return

        lifecycleScope.launch {
            try {
                val list: List<SpotRes> = ApiClient.api.getSpots(postId = id)

                spots.clear()
                spots.addAll(
                    list.map {
                        SpotUI(
                            id = it.id,
                            name = it.name,
                            description = it.description,
                            lat = it.lat,
                            lng = it.lng,
                            photoUrl = it.photoUrl
                        )
                    }
                )
                adapter.notifyDataSetChanged()
            } catch (e: Exception) {
                Toast.makeText(
                    this@SpotListActivity,
                    "景點載入失敗：${e.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun deleteSpotViaBackend(position: Int) {
        val id = postId ?: return
        val email = myEmail ?: run {
            Toast.makeText(this, "請先登入", Toast.LENGTH_SHORT).show()
            return
        }
        val spotId = spots.getOrNull(position)?.id ?: return

        lifecycleScope.launch {
            try {
                ApiClient.api.deleteSpot(
                    postId = id,
                    spotId = spotId,
                    email = email
                )
                spots.removeAt(position)
                adapter.notifyItemRemoved(position)
            } catch (e: Exception) {
                Toast.makeText(
                    this@SpotListActivity,
                    "刪除失敗：${e.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

class SpotAdapter(
    private val data: List<SpotUI>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<SpotViewHolder>() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SpotViewHolder {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_spot, parent, false)
        return SpotViewHolder(v)
    }

    override fun onBindViewHolder(holder: SpotViewHolder, position: Int) {
        val s = data[position]
        holder.title.text = s.name.ifBlank { "(未命名景點)" }
        holder.subtitle.text =
            "${s.description.take(30)}…  (${s.lat}, ${s.lng})"
        holder.btnDelete.setOnClickListener { onDelete(position) }
    }

    override fun getItemCount() = data.size
}

class SpotViewHolder(v: android.view.View) : RecyclerView.ViewHolder(v) {
    val title: android.widget.TextView = v.findViewById(R.id.tvTitle)
    val subtitle: android.widget.TextView = v.findViewById(R.id.tvSubtitle)
    val btnDelete: ImageButton = v.findViewById(R.id.btnDelete)
}
