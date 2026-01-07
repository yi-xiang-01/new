package com.example.mapcollection

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions

class MapsActivity2 : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var mMap: GoogleMap

    // ✅ 新版回傳
    private val infoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            // 目前你沒有要接資料就先留空
            // res.data?.getStringExtra(...)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_maps2)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val mapFragment =
            supportFragmentManager.findFragmentById(R.id.map2Fragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // 初始化懸浮輸入區
        val planningNameInput = findViewById<EditText>(R.id.planningNameInput)
        val planningTypeSpinner = findViewById<Spinner>(R.id.planningTypeSpinner)
        val confirmButton = findViewById<Button>(R.id.confirmButton)

        val planningTypes = arrayOf("規劃路線", "規劃景點")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, planningTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        planningTypeSpinner.adapter = adapter

        // 如果有傳入資料，預設顯示
        intent.getStringExtra("planningName")?.let { planningNameInput.setText(it) }
        intent.getStringExtra("planningType")?.let { t ->
            val idx = planningTypes.indexOf(t)
            if (idx >= 0) planningTypeSpinner.setSelection(idx)
        }

        confirmButton.setOnClickListener {
            val planningName = planningNameInput.text.toString()
            val planningType = planningTypeSpinner.selectedItem?.toString() ?: ""
            setResult(
                Activity.RESULT_OK,
                Intent().apply {
                    putExtra("planningName", planningName)
                    putExtra("planningType", planningType)
                }
            )
            finish()
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        val taiwan = LatLng(23.6978, 120.9605)
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(taiwan, 7f))
        mMap.addMarker(MarkerOptions().position(taiwan).title("台灣"))

        mMap.setOnMapClickListener { latLng ->
            // ✅ 點地圖就開 InformationActivity（AI 走後端）
            val i = Intent(this, InformationActivity::class.java).apply {
                putExtra("latitude", latLng.latitude)
                putExtra("longitude", latLng.longitude)
                putExtra("spotName", "選取位置") // 可改成你想顯示的文字
            }
            infoLauncher.launch(i)
        }
    }
}
