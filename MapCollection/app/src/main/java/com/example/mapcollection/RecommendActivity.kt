package com.example.mapcollection

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mapcollection.network.ApiClient
import com.example.mapcollection.network.PublicPostRes
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

/** 加權後要顯示的卡片資料 */
data class RecoPost(
    val id: String,
    val ownerEmail: String,
    val mapName: String,
    val mapType: String,
    val createdAtMillis: Long,
    val likes: Int,
    val score: Int
)

class RecommendActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private var tvEmpty: TextView? = null

    private val posts = mutableListOf<RecoPost>()
    private lateinit var adapter: RecoPostAdapter

    private var myEmail: String? = null
    private var myLabels: List<String> = emptyList()
    private var myFollowing: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_recommend)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }

        recycler = findViewById(R.id.recyclerRecommend)
        tvEmpty = findViewById(R.id.tvEmpty)

        recycler.layoutManager = GridLayoutManager(this, 3)
        adapter = RecoPostAdapter(posts) { pos ->
            val p = posts[pos]
            startActivity(
                Intent(this, PublicMapViewerActivity::class.java)
                    .putExtra("POST_ID", p.id)
                    .putExtra("MAP_TITLE", p.mapName)
                    .putExtra("MAP_TYPE", p.mapType)
                    .putExtra("OWNER_EMAIL", p.ownerEmail)
            )
        }
        recycler.adapter = adapter

        setupBottomNav()
    }

    override fun onResume() {
        super.onResume()
        myEmail = getSharedPreferences("Account", MODE_PRIVATE)
            .getString("LOGGED_IN_EMAIL", null)

        loadUserContextAndRecommendViaBackend()
    }

    private fun setupBottomNav() {
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnPath).setOnClickListener {
            startActivity(Intent(this, PathActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnProfile).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    /** ✅ 前後端分離：讀取我的 userLabel / following，完成後做規則式推薦 */
    private fun loadUserContextAndRecommendViaBackend() {
        val email = myEmail

        lifecycleScope.launch {
            try {
                if (email.isNullOrBlank()) {
                    myLabels = emptyList()
                    myFollowing = emptySet()
                } else {
                    // 1) userLabel 來自後端 /me/profile
                    val prof = ApiClient.api.getMyProfile(email)
                    val raw = prof.userLabel ?: ""
                    myLabels = raw.split(',', '、', '/', '｜', '|', ' ', '　')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { it.lowercase(Locale.getDefault()) }

                    // 2) following 來自後端 /me/following
                    //    你回傳的是 List<FollowUser>，取 email 即可
                    val followingUsers = ApiClient.api.getMyFollowing(email)
                    myFollowing = followingUsers.mapNotNull { it.email?.takeIf { s -> s.isNotBlank() } }.toSet()
                }

                // 3) 公開貼文清單 /posts/public
                fetchAndScoreViaBackend()

            } catch (_: Exception) {
                myLabels = emptyList()
                myFollowing = emptySet()
                fetchAndScoreViaBackend()
            }
        }
    }

    /** ✅ 前後端分離：從後端抓近 N 筆，做規則式加權與排序 */
    private suspend fun fetchAndScoreViaBackend() {
        try {
            val snap: List<PublicPostRes> = ApiClient.api.getPublicPosts(limit = 300)

            val now = System.currentTimeMillis()
            val labelSet = myLabels.toSet()

            val rows = snap.map { d ->
                val id = d.id
                val owner = d.ownerEmail
                val name = d.mapName
                val type = d.mapType
                val ms = d.createdAtMillis
                val likes = d.likes

                var score = 0

                // 1) 追蹤作者加權
                if (owner in myFollowing) score += 300

                // 2) 興趣標籤：出現在 mapType / mapName
                val nameL = name.lowercase(Locale.getDefault())
                val typeL = type.lowercase(Locale.getDefault())
                val labelHitsType = labelSet.count { it.isNotEmpty() && typeL.contains(it) }
                val labelHitsName = labelSet.count { it.isNotEmpty() && nameL.contains(it) }
                score += labelHitsType * 200 + labelHitsName * 120

                // 3) 熱度：likes 上限 100
                score += max(0, likes.coerceAtMost(100))

                // 4) 新鮮度：近 30 天給 0~120 分
                val days = if (ms > 0L) ((now - ms) / 86_400_000L).toInt() else 999
                val recency = (120 - days * 4).coerceIn(0, 120)
                score += recency

                RecoPost(
                    id = id,
                    ownerEmail = owner,
                    mapName = name,
                    mapType = type,
                    createdAtMillis = ms,
                    likes = likes,
                    score = score
                )
            }

            val sorted = rows.sortedWith(
                compareByDescending<RecoPost> { it.score }
                    .thenByDescending { it.createdAtMillis }
            )

            posts.clear()
            posts.addAll(sorted)
            adapter.notifyDataSetChanged()

            tvEmpty?.visibility = if (posts.isEmpty()) View.VISIBLE else View.GONE

        } catch (_: Exception) {
            posts.clear()
            adapter.notifyDataSetChanged()
            tvEmpty?.visibility = View.VISIBLE
        }
    }
}

/** 只有瀏覽（唯讀），點擊卡片開地圖檢視頁 */
class RecoPostAdapter(
    private val posts: List<RecoPost>,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<RecoPostAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val mapNameText: TextView = v.findViewById(R.id.mapNameText)
        val mapTypeText: TextView = v.findViewById(R.id.mapTypeText)
        val btnDelete: android.widget.ImageButton = v.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.card_post, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = posts[pos]
        h.mapNameText.text = p.mapName
        h.mapTypeText.text = "${p.mapType} • by ${p.ownerEmail}"
        h.btnDelete.visibility = View.GONE
        h.itemView.setOnClickListener { onItemClick(pos) }
    }

    override fun getItemCount() = posts.size
}
