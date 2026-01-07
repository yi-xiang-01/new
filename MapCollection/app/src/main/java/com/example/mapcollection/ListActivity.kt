package com.example.mapcollection

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class ListActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)

        // 返回上一頁
        findViewById<MaterialButton>(R.id.btnback).setOnClickListener { finish() }

        // 登出（前後端分離：清本地登入狀態即可）
        findViewById<android.widget.Button>(R.id.btnLogout).setOnClickListener {

            // 先拿 email（清偏好前先取出）
            val email = getSharedPreferences("Account", MODE_PRIVATE)
                .getString("LOGGED_IN_EMAIL", null)

            // ✅ 只清本地快取（前後端分離不依賴 Firebase Auth 狀態）
            getSharedPreferences("Account", MODE_PRIVATE).edit().clear().apply()
            getSharedPreferences("MapCollection", MODE_PRIVATE).edit().clear().apply()
            if (email != null) {
                getSharedPreferences("Profile_$email", MODE_PRIVATE).edit().clear().apply()
            }

            // 回登入頁並清空返回堆疊，避免返回鍵回到主頁
            val i = Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(i)
        }

        // 進入我的收藏（你已經是 Retrofit 打 /me/favorites）
        findViewById<android.widget.Button>(R.id.btnOpenFavorites).setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }

        // 進入我追蹤的人（你已經是 Retrofit 打 /me/following）
        findViewById<android.widget.Button>(R.id.btnOpenFollowing).setOnClickListener {
            startActivity(Intent(this, FollowingActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()

        // ✅ 前後端分離：用本地登入資訊判斷（SharedPreferences）
        val email = getSharedPreferences("Account", MODE_PRIVATE)
            .getString("LOGGED_IN_EMAIL", null)

        if (email.isNullOrBlank()) {
            val i = Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(i)
        }
    }
}
