package com.example.mapcollection

import android.content.Intent
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.mapcollection.network.ApiClient
import com.example.mapcollection.network.PublicPostRes
import com.example.mapcollection.network.PublicUserProfileRes
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Locale

class UserPublicProfileActivity : AppCompatActivity() {

    private var targetEmail: String? = null

    // 個資 UI
    private lateinit var userNameText: TextView
    private lateinit var introductionText: TextView
    private lateinit var imgProfile: ImageView
    private lateinit var chipGroupLabels: ChipGroup

    // 列表
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private val posts = mutableListOf<PublicPostRes>()
    private lateinit var adapter: PubPostAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_public_profile)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(s.left, s.top, s.right, 0)
            insets
        }

        targetEmail = intent.getStringExtra("TARGET_EMAIL")

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        userNameText = findViewById(R.id.userName)
        introductionText = findViewById(R.id.introduction)
        imgProfile = findViewById(R.id.imgProfile)
        chipGroupLabels = findViewById(R.id.chipGroupLabels)

        recyclerView = findViewById(R.id.recyclerView)
        tvEmpty = findViewById(R.id.tvEmpty)

        recyclerView.layoutManager = GridLayoutManager(this, 3)
        adapter = PubPostAdapter(posts) { pos ->
            val p = posts[pos]
            startActivity(
                Intent(this, PublicMapViewerActivity::class.java)
                    .putExtra("POST_ID", p.id)
                    .putExtra("MAP_TITLE", p.mapName)
                    .putExtra("MAP_TYPE", p.mapType)
                    .putExtra("OWNER_EMAIL", p.ownerEmail)
            )
        }
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadProfileViaBackend()
        loadUserPostsViaBackend()
    }

    // ---------- 個人資料（改打後端） ----------

    private fun loadProfileViaBackend() {
        val email = targetEmail ?: return
        lifecycleScope.launch {
            try {
                val res: PublicUserProfileRes = ApiClient.api.getUserPublicProfile(email)

                userNameText.text = res.userName.ifBlank { res.email }
                introductionText.text = res.introduction
                renderLabelChips(res.userLabel)

                if (!res.photoUrl.isNullOrBlank()) {
                    Glide.with(this@UserPublicProfileActivity).load(res.photoUrl).into(imgProfile)
                } else {
                    imgProfile.setImageResource(R.drawable.ic_launcher_foreground)
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(R.id.main), "載入個人資料失敗：${e.localizedMessage}", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderLabelChips(raw: String) {
        chipGroupLabels.removeAllViews()

        val tokens = raw
            .replace("，", ",")
            .replace("、", ",")
            .split(',', '#', ' ', '｜', '|', '/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        for (t in tokens) {
            val themedCtx = try { ContextThemeWrapper(this, R.style.ChipStyle_Label) } catch (_: Exception) { this }
            val chip = Chip(themedCtx, null, 0).apply {
                text = t
                isCheckable = false
                isClickable = false
                setEnsureMinTouchTargetSize(false)
            }
            chipGroupLabels.addView(chip)
        }
    }

    // ---------- 對方貼文（改打後端） ----------

    private fun loadUserPostsViaBackend() {
        val email = targetEmail ?: return
        lifecycleScope.launch {
            try {
                val list = ApiClient.api.getUserPostsPublic(email = email, limit = 300)
                posts.clear()
                posts.addAll(list)
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (posts.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                posts.clear()
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = View.VISIBLE
                Snackbar.make(findViewById(R.id.main), "載入貼文失敗：${e.localizedMessage}", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    class PubPostAdapter(
        private val posts: List<PublicPostRes>,
        private val onItemClick: (Int) -> Unit
    ) : RecyclerView.Adapter<PubPostAdapter.VH>() {

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
}
