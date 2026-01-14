package com.example.mapcollection

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText

class NewPointActivity : AppCompatActivity() {

    private lateinit var edSpotName: TextInputEditText
    private lateinit var edSpotDescription: TextInputEditText
    private lateinit var imgSpotPhoto: ImageView
    private lateinit var btnUploadPhoto: Button
    private lateinit var btnPickCoord: Button
    private lateinit var confirmButton: Button

    private var selectedPhotoUri: Uri? = null
    private var selectedLat: Double? = null
    private var selectedLng: Double? = null

    private lateinit var imagePicker: ActivityResultLauncher<String>
    private lateinit var mapPicker: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.card_newpoint)

        edSpotName = findViewById(R.id.edSpotName)
        edSpotDescription = findViewById(R.id.edSpotDescription)
        imgSpotPhoto = findViewById(R.id.imgSpotPhoto)
        btnUploadPhoto = findViewById(R.id.btnUploadPhoto)
        btnPickCoord = findViewById(R.id.btnPickCoord)
        confirmButton = findViewById(R.id.confirmButton)
        val btnBack2 = findViewById<ImageButton>(R.id.btnback2)

        // 相簿選圖
        imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                selectedPhotoUri = it
                imgSpotPhoto.setImageURI(it)
            }
        }

        // 地圖選點（接回 latitude/longitude）
        mapPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                val data = res.data
                // ✅ 統一 key：latitude/longitude
                selectedLat = data?.getDoubleExtra("latitude", 0.0)
                selectedLng = data?.getDoubleExtra("longitude", 0.0)
                Snackbar.make(confirmButton, "座標已選：$selectedLat, $selectedLng", Snackbar.LENGTH_SHORT).show()
            }
        }

        btnUploadPhoto.setOnClickListener { imagePicker.launch("image/*") }

        btnPickCoord.setOnClickListener {
            val i = Intent(this, PickLocationNewActivity::class.java).apply {
                // ✅ 如果已選過，帶回去當預設點（key 也統一）
                if (selectedLat != null && selectedLng != null) {
                    putExtra("latitude", selectedLat!!)
                    putExtra("longitude", selectedLng!!)
                }
            }
            mapPicker.launch(i)
        }

        confirmButton.setOnClickListener { v ->
            val name = edSpotName.text?.toString()?.trim().orEmpty()
            val desc = edSpotDescription.text?.toString()?.trim().orEmpty()

            if (name.isBlank()) {
                Snackbar.make(v, "請輸入景點名稱", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedLat == null || selectedLng == null) {
                Snackbar.make(v, "請先選擇座標", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // ✅ 回傳給 MapEditorActivity：文字 + 座標 + (可選)照片 uri
            val result = Intent().apply {
                putExtra("spotName", name)
                putExtra("spotDescription", desc)
                putExtra("latitude", selectedLat!!)
                putExtra("longitude", selectedLng!!)
                putExtra("photoUri", selectedPhotoUri?.toString()) // ✅ 新增：給上層上傳用
            }
            setResult(Activity.RESULT_OK, result)
            finish()
        }

        btnBack2.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }
}
