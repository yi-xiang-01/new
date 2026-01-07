package com.example.mapcollection

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mapcollection.network.ApiClient
import com.example.mapcollection.network.FavPost
import kotlinx.coroutines.launch

class FavoritesActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private val data = mutableListOf<FavPost>()
    private lateinit var adapter: FavPostAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)

        rv = findViewById(R.id.recyclerFavorites)
        rv.layoutManager = GridLayoutManager(this, 3)

        adapter = FavPostAdapter(data)
        rv.adapter = adapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        loadFavorites()
    }

    private fun loadFavorites() {
        val me = getSharedPreferences("Account", MODE_PRIVATE)
            .getString("LOGGED_IN_EMAIL", null)

        android.util.Log.d("Fav", "me=$me")

        if (me.isNullOrBlank()) {
            android.widget.Toast.makeText(this, "LOGGED_IN_EMAIL 為空", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val list = ApiClient.api.getMyFavorites(me)

                android.util.Log.d("Fav", "favorites size = ${list.size}")
                if (list.isNotEmpty()) {
                    android.util.Log.d("Fav", "first = ${list[0].mapName} / ${list[0].mapType}")
                }

                android.widget.Toast.makeText(
                    this@FavoritesActivity,
                    "載入收藏 ${list.size} 筆",
                    android.widget.Toast.LENGTH_SHORT
                ).show()

                data.clear()
                data.addAll(list)
                adapter.notifyDataSetChanged()
            } catch (e: Exception) {
                e.printStackTrace()
                android.widget.Toast.makeText(
                    this@FavoritesActivity,
                    "載入收藏失敗：${e.localizedMessage}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

/* ================= Adapter ================= */

class FavPostAdapter(private val posts: List<FavPost>) :
    RecyclerView.Adapter<FavPostAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.mapNameText)
        val type: TextView = v.findViewById(R.id.mapTypeText)
        val btnDelete: android.widget.ImageButton = v.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.card_post, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = posts[pos]

        android.util.Log.d("Fav", "bind: ${p.mapName}")

        h.title.text = p.mapName
        h.type.text = p.mapType
        h.btnDelete.visibility = View.GONE

        h.itemView.setOnClickListener {
            val ctx = h.itemView.context
            ctx.startActivity(
                Intent(ctx, PublicMapViewerActivity::class.java)
                    .putExtra("POST_ID", p.id)
                    .putExtra("MAP_TITLE", p.mapName)
                    .putExtra("MAP_TYPE", p.mapType)
                    .putExtra("OWNER_EMAIL", p.ownerEmail)
            )
        }
    }

    override fun getItemCount() = posts.size
}
