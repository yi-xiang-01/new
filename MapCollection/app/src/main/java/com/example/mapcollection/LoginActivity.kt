package com.example.mapcollection

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import androidx.appcompat.app.AppCompatActivity
import com.example.mapcollection.databinding.ActivityLoginBinding
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val db = Firebase.firestore
    private val auth = Firebase.auth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ 只相信 Firebase Auth 是否已登入
        val current = auth.currentUser
        if (current?.email != null) {
            rememberEmail(current.email!!)
            checkIfFirstLogin(current.email!!)
            return
        } else {
            // 若本地殘留 email（理論上登出後會清），保險清掉避免狀態混亂
            getSharedPreferences("Account", MODE_PRIVATE).edit().remove("LOGGED_IN_EMAIL").apply()
        }

        binding.btnLogin.setOnClickListener { loginUser() }
        binding.tvGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }
    }

    private fun loginUser() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val pwd = binding.etPassword.text?.toString().orEmpty()
        if (!validate(email, pwd)) return

        // 1) Firebase Auth 登入
        auth.signInWithEmailAndPassword(email, pwd)
            .addOnSuccessListener {
                rememberEmail(email)
                show("登入成功！歡迎 $email")
                checkIfFirstLogin(email)
            }
            .addOnFailureListener {
                // 2) 失敗 → 試舊資料（Firestore）並自動遷移（你原本的功能保留）
                migrateLegacyUserIfMatch(email, pwd)
            }
    }

    /** 舊帳號遷移：Firestore 有明文密碼且相符 → 立即在 Auth 建立帳號並合併 uid */
    private fun migrateLegacyUserIfMatch(email: String, pwd: String) {
        val docRef = db.collection("users").document(email)
        docRef.get()
            .addOnSuccessListener { doc ->
                val legacyPwd = doc.getString("password")
                if (doc.exists() && legacyPwd == pwd) {
                    auth.createUserWithEmailAndPassword(email, pwd)
                        .addOnSuccessListener { res ->
                            val uid = res.user?.uid ?: ""
                            docRef.set(mapOf("uid" to uid), SetOptions.merge())
                                .addOnCompleteListener {
                                    rememberEmail(email)
                                    show("已為舊帳號建立登入方式，歡迎回來！")
                                    checkIfFirstLogin(email)
                                }
                        }
                        .addOnFailureListener { e2 ->
                            show("登入失敗：${e2.localizedMessage}")
                        }
                } else {
                    show("帳號或密碼錯誤")
                }
            }
            .addOnFailureListener { e ->
                show("登入失敗：${e.localizedMessage}")
            }
    }

    // 用 Firestore 的 firstLogin 決定導向
    private fun checkIfFirstLogin(email: String) {
        db.collection("users").document(email).get()
            .addOnSuccessListener { doc ->
                val first = doc.getBoolean("firstLogin") ?: true
                if (first) {
                    startActivity(Intent(this, EditProfileActivity::class.java))
                } else {
                    startActivity(Intent(this, MainActivity::class.java).putExtra("USER_EMAIL", email))
                }
                finish()
            }
            .addOnFailureListener { e ->
                show("檢查個人資料失敗：${e.localizedMessage}")
                startActivity(Intent(this, MainActivity::class.java).putExtra("USER_EMAIL", email))
                finish()
            }
    }

    private fun rememberEmail(email: String) {
        getSharedPreferences("Account", MODE_PRIVATE)
            .edit()
            .putString("LOGGED_IN_EMAIL", email)
            .apply()
    }

    private fun validate(email: String, pwd: String): Boolean {
        if (email.isBlank() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            show("請輸入正確的 Email"); return false
        }
        if (pwd.isBlank()) {
            show("請輸入密碼"); return false
        }
        return true
    }

    private fun show(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }
}
