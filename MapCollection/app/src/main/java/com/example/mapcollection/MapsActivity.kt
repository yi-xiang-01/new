package com.example.mapcollection

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.mapcollection.network.ApiClient
import com.example.mapcollection.network.CreatePostReq
import com.example.mapcollection.network.UpdatePostReq
import kotlinx.coroutines.launch

class MapsActivity : AppCompatActivity() {

    private lateinit var mapNameInput: EditText
    private lateinit var mapTypeInput: EditText
    private lateinit var confirmButton: Button

    private var currentEmail: String? = null
    private var postId: String? = null // ✅ 有值=編輯，null=新增

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)

        mapNameInput = findViewById(R.id.edSpotName)
        mapTypeInput = findViewById(R.id.edMapType)
        confirmButton = findViewById(R.id.confirmButton)

        currentEmail = getSharedPreferences("Account", MODE_PRIVATE)
            .getString("LOGGED_IN_EMAIL", null)

        if (currentEmail.isNullOrBlank()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // ✅ 編輯模式用：從上一頁帶入 postId（如果你原本沒帶，等下照我說的改上一頁）
        postId = intent.getStringExtra("postId")

        // 若有傳入資料，預設顯示
        intent.getStringExtra("mapName")?.let { mapNameInput.setText(it) }
        intent.getStringExtra("mapType")?.let { mapTypeInput.setText(it) }

        confirmButton.setOnClickListener {
            val mapName = mapNameInput.text.toString().trim()
            val mapType = mapTypeInput.text.toString().trim()

            if (mapName.isBlank() || mapType.isBlank()) {
                AlertDialog.Builder(this)
                    .setTitle("提醒")
                    .setMessage("請輸入地圖名稱與地圖種類")
                    .setPositiveButton("確定", null)
                    .show()
                return@setOnClickListener
            }

            if (postId.isNullOrBlank()) {
                createPostViaBackend(mapName, mapType)
            } else {
                updatePostViaBackend(postId!!, mapName, mapType)
            }
        }
    }

    private fun createPostViaBackend(mapName: String, mapType: String) {
        val email = currentEmail ?: return

        val loading = AlertDialog.Builder(this)
            .setMessage("建立中...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val res = ApiClient.api.createMyPost(
                    CreatePostReq(
                        email = email,
                        mapName = mapName,
                        mapType = mapType,
                        isRecommended = false
                    )
                )
                loading.dismiss()

                // ✅ 回傳給上一頁（含 postId）
                setResult(
                    Activity.RESULT_OK,
                    Intent().apply {
                        putExtra("postId", res.id)
                        putExtra("mapName", mapName)
                        putExtra("mapType", mapType)
                    }
                )
                finish()
            } catch (e: Exception) {
                loading.dismiss()
                AlertDialog.Builder(this@MapsActivity)
                    .setTitle("建立失敗")
                    .setMessage(e.localizedMessage ?: "未知錯誤")
                    .setPositiveButton("確定", null)
                    .show()
            }
        }
    }

    private fun updatePostViaBackend(postId: String, mapName: String, mapType: String) {
        val email = currentEmail ?: return

        val loading = AlertDialog.Builder(this)
            .setMessage("儲存中...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                ApiClient.api.updateMyPost(
                    postId = postId,
                    req = UpdatePostReq(
                        email = email,
                        mapName = mapName,
                        mapType = mapType
                    )
                )
                loading.dismiss()

                // ✅ 回傳給上一頁
                setResult(
                    Activity.RESULT_OK,
                    Intent().apply {
                        putExtra("postId", postId)
                        putExtra("mapName", mapName)
                        putExtra("mapType", mapType)
                    }
                )
                finish()
            } catch (e: Exception) {
                loading.dismiss()
                AlertDialog.Builder(this@MapsActivity)
                    .setTitle("儲存失敗")
                    .setMessage(e.localizedMessage ?: "未知錯誤")
                    .setPositiveButton("確定", null)
                    .show()
            }
        }
    }
}
