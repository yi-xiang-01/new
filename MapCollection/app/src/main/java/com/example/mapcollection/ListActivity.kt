package com.example.mapcollection

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class ListActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)

        // 返回上一頁
        findViewById<MaterialButton>(R.id.btnback).setOnClickListener { finish() }

        // ✅ 登出：Firebase Auth + 本地快取都要清
        findViewById<android.widget.Button>(R.id.btnLogout).setOnClickListener {

            // 先拿 email（清偏好前先取出）
            val email = getSharedPreferences("Account", MODE_PRIVATE)
                .getString("LOGGED_IN_EMAIL", null)

            // ✅ 1) Firebase Auth 真正登出（最關鍵）
            Firebase.auth.signOut()

            // ✅ 2) 清本地
            getSharedPreferences("Account", MODE_PRIVATE).edit().clear().apply()
            getSharedPreferences("MapCollection", MODE_PRIVATE).edit().clear().apply()
            if (email != null) {
                getSharedPreferences("Profile_$email", MODE_PRIVATE).edit().clear().apply()
            }

            // ✅ 3) 回登入頁並清空返回堆疊（避免按返回回到主頁）
            val i = Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(i)
        }

        // 進入我的收藏
        findViewById<android.widget.Button>(R.id.btnOpenFavorites).setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }

        // 進入我追蹤的人
        findViewById<android.widget.Button>(R.id.btnOpenFollowing).setOnClickListener {
            startActivity(Intent(this, FollowingActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()

        // ✅ 用 Firebase Auth 判斷是否登入（更準）
        val user = Firebase.auth.currentUser
        if (user?.email.isNullOrBlank()) {
            val i = Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(i)
        }
    }
}
