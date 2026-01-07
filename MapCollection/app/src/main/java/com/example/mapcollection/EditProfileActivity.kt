package com.example.mapcollection

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.example.mapcollection.network.ApiClient
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import com.example.mapcollection.network.UpdateProfileReq

class EditProfileActivity : AppCompatActivity() {

    private lateinit var imgUserPhoto: ImageView
    private var selectedImageBytes: ByteArray? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                selectedImageBytes = decodeAndCompress(it) // 壓縮到 1280px、JPEG 85
                if (selectedImageBytes != null) {
                    val bmp = BitmapFactory.decodeByteArray(
                        selectedImageBytes, 0, selectedImageBytes!!.size
                    )
                    imgUserPhoto.setImageBitmap(bmp)
                } else {
                    show("載入圖片失敗：取得的資料為空")
                }
            } catch (e: Exception) {
                show("載入圖片失敗：${e.localizedMessage}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_editprofile)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        val edUserName = findViewById<EditText>(R.id.edUserName)
        val edUserLabel = findViewById<EditText>(R.id.edUserLabel)
        val edIntroduction = findViewById<EditText>(R.id.edIntroduction)
        val btnSave = findViewById<Button>(R.id.btnSave)
        imgUserPhoto = findViewById(R.id.imgUserPhoto)

        // hint
        edUserName.hint = "使用者姓名"
        edUserLabel.hint = "個人化標籤"
        edIntroduction.hint = "個人簡介"

        // 先用傳入值預填（保留你的原本行為）
        val currentUserName = intent.getStringExtra("currentUserName").orEmpty()
        val currentUserLabel = intent.getStringExtra("currentUserLabel").orEmpty()
        val currentIntroduction = intent.getStringExtra("currentIntroduction").orEmpty()
        if (currentUserName.isNotBlank() && currentUserName != "使用者姓名") edUserName.setText(currentUserName)
        if (currentUserLabel.isNotBlank() && currentUserLabel != "個人化標籤") edUserLabel.setText(currentUserLabel)
        if (currentIntroduction.isNotBlank() && currentIntroduction != "個人簡介") edIntroduction.setText(currentIntroduction)

        // 讀 email（你目前專案用 SharedPreferences 記登入 email）
        val email = getSharedPreferences("Account", MODE_PRIVATE).getString("LOGGED_IN_EMAIL", null)

        // ✅ 從 Flask 拉一次雲端資料（取代 Firestore）
        if (email != null) {
            lifecycleScope.launch {
                try {
                    val profile = ApiClient.api.getMyProfile(email)

                    if (edUserName.text.isNullOrBlank() && profile.userName.isNotBlank()) {
                        edUserName.setText(profile.userName)
                    }
                    if (edUserLabel.text.isNullOrBlank() && profile.userLabel.isNotBlank()) {
                        edUserLabel.setText(profile.userLabel)
                    }
                    if (edIntroduction.text.isNullOrBlank() && profile.introduction.isNotBlank()) {
                        edIntroduction.setText(profile.introduction)
                    }
                    if (!profile.photoUrl.isNullOrBlank()) {
                        Glide.with(this@EditProfileActivity).load(profile.photoUrl).into(imgUserPhoto)
                    }
                } catch (e: Exception) {
                    // 拉不到就先不擋你編輯
                    toast("讀取個資失敗：${e.localizedMessage}")
                }
            }
        }

        // 選圖
        imgUserPhoto.setOnClickListener { imagePickerLauncher.launch("image/*") }

        // 存檔
        btnSave.setOnClickListener {
            val userName = edUserName.text.toString()
            val userLabel = edUserLabel.text.toString()
            val introduction = edIntroduction.text.toString()

            // 先把基本資料回傳給呼叫方（主頁可立即更新）
            val resultIntent = Intent().apply {
                putExtra("userName", userName)
                putExtra("userLabel", userLabel)
                putExtra("introduction", introduction)
                if (selectedImageBytes != null) putExtra("userPhoto", selectedImageBytes)
            }
            setResult(Activity.RESULT_OK, resultIntent)

            if (email == null) {
                toast("找不到登入資訊（email）")
                finish()
                return@setOnClickListener
            }

            // ✅ 改成打 Flask：先更新文字，再視情況上傳照片
            lifecycleScope.launch {
                try {
                    // 1) 先更新文字資料
                    ApiClient.api.updateMyProfile(
                        UpdateProfileReq(
                            email = email,
                            userName = userName,
                            userLabel = userLabel,
                            introduction = introduction,
                            firstLogin = false
                        )
                    )

                    // 2) 有換照片才上傳（multipart）
                    val bytes = selectedImageBytes
                    if (bytes != null) {
                        val photoUrl = uploadPhotoToFlask(email, bytes)
                        // 可選：把 photoUrl 再回寫一次（讓後端保存最新照片網址）
                        ApiClient.api.updateMyProfile(
                            UpdateProfileReq(
                                email = email,
                                userName = userName,
                                userLabel = userLabel,
                                introduction = introduction,
                                firstLogin = false,
                                photoUrl = photoUrl
                            )
                        )
                        toast("個資已更新（含照片）")
                    } else {
                        toast("個資已更新")
                    }

                    // 返回主頁
                    finish()
                } catch (e: Exception) {
                    toast("更新失敗：${e.localizedMessage}")
                }
            }
        }
    }

    /**
     * 把 ByteArray 寫成暫存檔 -> Retrofit Multipart 上傳
     * 後端回傳 photoUrl
     */
    private suspend fun uploadPhotoToFlask(email: String, bytes: ByteArray): String {
        // 暫存檔
        val tmp = File(cacheDir, "profile_${System.currentTimeMillis()}.jpg")
        FileOutputStream(tmp).use { it.write(bytes) }

        val emailPart = email.toRequestBody("text/plain".toMediaType())
        val fileBody = tmp.asRequestBody("image/jpeg".toMediaType())
        val photoPart = MultipartBody.Part.createFormData("photo", tmp.name, fileBody)

        val res = ApiClient.api.uploadMyPhoto(emailPart, photoPart)

        // 清掉暫存
        runCatching { tmp.delete() }

        return res.photoUrl
    }

    private fun show(msg: String) {
        Snackbar.make(findViewById(R.id.main), msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * 讀取 Uri → 壓縮成 JPEG ByteArray
     * - 最長邊縮到 1280px（避免超大圖上傳/OutOfMemory）
     * - JPEG 品質 85
     */
    private fun decodeAndCompress(uri: Uri, maxEdge: Int = 1280, quality: Int = 85): ByteArray? {
        val input = contentResolver.openInputStream(uri) ?: return null
        val original = BitmapFactory.decodeStream(input) ?: return null
        input.close()

        val w = original.width
        val h = original.height
        val longest = max(w, h).toFloat()
        val scale = if (longest > maxEdge) maxEdge / longest else 1f
        val targetW = (w * scale).toInt().coerceAtLeast(1)
        val targetH = (h * scale).toInt().coerceAtLeast(1)

        val bmp: Bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(original, targetW, targetH, true)
        } else original

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (bmp !== original) bmp.recycle()
        return out.toByteArray()
    }
}
