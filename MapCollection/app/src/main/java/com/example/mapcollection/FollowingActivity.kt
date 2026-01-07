package com.example.mapcollection

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.mapcollection.network.ApiClient
import com.example.mapcollection.network.FollowUser
import kotlinx.coroutines.launch

class FollowingActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private val data = mutableListOf<FollowUser>()
    private lateinit var adapter: FollowingAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_following)

        rv = findViewById(R.id.recyclerFollowing)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = FollowingAdapter(data)
        rv.adapter = adapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        loadFollowing()
    }

    private fun loadFollowing() {
        val me = getSharedPreferences("Account", MODE_PRIVATE)
            .getString("LOGGED_IN_EMAIL", null)

        if (me.isNullOrBlank()) {
            android.widget.Toast.makeText(this, "LOGGED_IN_EMAIL 為空", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val list = ApiClient.api.getMyFollowing(me)
                data.clear()
                data.addAll(list)
                adapter.notifyDataSetChanged()
            } catch (e: Exception) {
                e.printStackTrace()
                android.widget.Toast.makeText(
                    this@FollowingActivity,
                    "載入追蹤清單失敗：${e.localizedMessage}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

class FollowingAdapter(private val users: List<FollowUser>) :
    RecyclerView.Adapter<FollowingAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.ivAvatar)
        val name: TextView = v.findViewById(R.id.tvName)
        val intro: TextView = v.findViewById(R.id.tvIntro)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_follow_user, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val u = users[pos]

        h.name.text = if (u.userName.isNotBlank()) u.userName else u.email
        h.intro.text = u.introduction

        if (!u.photoUrl.isNullOrEmpty()) {
            Glide.with(h.itemView).load(u.photoUrl).into(h.avatar)
        } else {
            h.avatar.setImageResource(R.drawable.ic_launcher_foreground)
        }

        h.itemView.setOnClickListener {
            val ctx = h.itemView.context
            ctx.startActivity(
                Intent(ctx, UserPublicProfileActivity::class.java)
                    .putExtra("TARGET_EMAIL", u.email)
            )
        }
    }

    override fun getItemCount() = users.size
}
