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
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRegister.setOnClickListener { registerUserViaBackend() }
        binding.tvGoLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun registerUserViaBackend() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val pwd = binding.etPassword.text?.toString().orEmpty()
        val confirmPwd = binding.etConfirmPassword.text?.toString().orEmpty()

        if (!validate(email, pwd, confirmPwd)) return

        binding.btnRegister.isEnabled = false

        lifecycleScope.launch {
            try {
                // ✅ 呼叫 Flask：POST /auth/register
                ApiClient.api.register(
                    RegisterReq(
                        email = email,
                        password = pwd
                    )
                )

                // ✅ 註冊成功後：先把 email 存起來（你目前 app 的登入狀態是靠 LOGGED_IN_EMAIL）
                getSharedPreferences("Account", MODE_PRIVATE)
                    .edit()
                    .putString("LOGGED_IN_EMAIL", email)
                    .apply()

                show("註冊成功！請先設定個人資料")
                startActivity(Intent(this@RegisterActivity, EditProfileActivity::class.java))
                finish()

            } catch (e: Exception) {
                show("註冊失敗：${e.localizedMessage}")
                binding.btnRegister.isEnabled = true
            }
        }
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
