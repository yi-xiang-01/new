package com.example.mapcollection

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.mapcollection.databinding.ActivityRegisterBinding
import com.example.mapcollection.network.ApiClient
import com.example.mapcollection.network.RegisterReq
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import retrofit2.HttpException

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRegister.setOnClickListener { registerUserViaBackendThenLogin() }
        binding.tvGoLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun registerUserViaBackendThenLogin() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val pwd = binding.etPassword.text?.toString().orEmpty()
        val confirmPwd = binding.etConfirmPassword.text?.toString().orEmpty()

        if (!validate(email, pwd, confirmPwd)) return

        binding.btnRegister.isEnabled = false

        lifecycleScope.launch {
            try {
                // ✅ 1) 先打後端：建立 Firebase Auth 帳號 + Firestore users/{email}
                ApiClient.api.register(RegisterReq(email = email, password = pwd))

                // ✅ 2) 註冊成功後：立刻用 Firebase Auth 登入，讓狀態一致
                Firebase.auth.signInWithEmailAndPassword(email, pwd)
                    .addOnSuccessListener {
                        rememberEmail(email)
                        show("註冊成功！請先設定個人資料")
                        startActivity(Intent(this@RegisterActivity, EditProfileActivity::class.java))
                        finish()
                    }
                    .addOnFailureListener { e ->
                        // 後端已註冊成功，但這裡登入失敗（通常是網路/設定）
                        binding.btnRegister.isEnabled = true
                        show("註冊成功，但自動登入失敗：${e.localizedMessage}")
                    }

            } catch (e: HttpException) {
                binding.btnRegister.isEnabled = true
                if (e.code() == 409) {
                    show("這個 Email 已註冊過了")
                } else {
                    show("註冊失敗（HTTP ${e.code()}）：${e.message()}")
                }
            } catch (e: Exception) {
                binding.btnRegister.isEnabled = true
                show("註冊失敗：${e.localizedMessage}")
            }
        }
    }

    private fun rememberEmail(email: String) {
        getSharedPreferences("Account", MODE_PRIVATE)
            .edit()
            .putString("LOGGED_IN_EMAIL", email)
            .apply()
    }

    private fun validate(email: String, pwd: String, confirmPwd: String): Boolean {
        if (email.isBlank() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            show("請輸入正確的 Email"); return false
        }
        if (pwd.length < 6) {
            show("密碼至少 6 碼"); return false
        }
        if (pwd != confirmPwd) {
            show("兩次輸入的密碼不一致"); return false
        }
        return true
    }

    private fun show(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }
}
