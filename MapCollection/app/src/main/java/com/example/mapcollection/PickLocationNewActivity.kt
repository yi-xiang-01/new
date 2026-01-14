package com.example.mapcollection

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener
import java.util.Locale

class PickLocationNewActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var map: GoogleMap
    private var pickedMarker: Marker? = null

    private var pickedLatLng: LatLng? = null
    private var pickedName: String = ""
    private var pickedPlaceId: String? = null

    private lateinit var tvPickedName: TextView
    private lateinit var btnConfirm: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pick_location_new)

        tvPickedName = findViewById(R.id.tvPickedName)
        btnConfirm = findViewById(R.id.btnConfirm)

        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
        btnConfirm.isEnabled = false

        // ✅ Places init（沿用 Manifest 的 com.google.android.geo.API_KEY）
        initPlacesIfNeeded()

        setupPlacesAutocomplete()

        val mapFrag = supportFragmentManager.findFragmentById(R.id.mapPick) as SupportMapFragment
        mapFrag.getMapAsync(this)

        // ✅ 回傳座標 + 名稱 + placeId
        btnConfirm.setOnClickListener {
            val latLng = pickedLatLng ?: run {
                Toast.makeText(this, "請先選擇地點", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val result = Intent().apply {
                putExtra("latitude", latLng.latitude)
                putExtra("longitude", latLng.longitude)
                putExtra("placeName", pickedName.ifBlank { "新景點" })
                putExtra("placeId", pickedPlaceId) // ✅ 關鍵
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun initPlacesIfNeeded() {
        if (Places.isInitialized()) return

        val appInfo = packageManager.getApplicationInfo(
            packageName,
            PackageManager.GET_META_DATA
        )
        val apiKey = appInfo.metaData.getString("com.google.android.geo.API_KEY") ?: ""

        if (apiKey.isBlank()) {
            Toast.makeText(this, "找不到 Google Maps API KEY（Manifest meta-data）", Toast.LENGTH_LONG).show()
            return
        }
        Places.initialize(applicationContext, apiKey, Locale.TAIWAN)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isMapToolbarEnabled = false

        // 預設台灣
        map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(23.6978, 120.9605),
                7f
            )
        )

        // ✅ 只允許點 POI（才會有 placeId）
        map.setOnPoiClickListener { poi ->
            pickedLatLng = poi.latLng
            pickedName = poi.name
            pickedPlaceId = poi.placeId

            tvPickedName.text = "已選：$pickedName"

            pickedMarker?.remove()
            pickedMarker = map.addMarker(
                MarkerOptions().position(poi.latLng).title(pickedName)
            )
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(poi.latLng, 16f))
            btnConfirm.isEnabled = true
        }

        map.setOnMapClickListener {
            Toast.makeText(this, "請點選地圖上的地點（POI）或使用上方搜尋", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPlacesAutocomplete() {
        val container = findViewById<FrameLayout>(R.id.placesContainer)

        val acFrag = AutocompleteSupportFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(container.id, acFrag)
            .commitNow()

        acFrag.setPlaceFields(
            listOf(
                Place.Field.ID,
                Place.Field.NAME,
                Place.Field.LAT_LNG,
                Place.Field.TYPES
            )
        )
        acFrag.setHint("搜尋地點（Google）")

        acFrag.setOnPlaceSelectedListener(object : PlaceSelectionListener {
            override fun onPlaceSelected(place: Place) {
                val latLng = place.latLng ?: run {
                    Toast.makeText(this@PickLocationNewActivity, "此地點沒有座標，請換一個", Toast.LENGTH_SHORT).show()
                    return
                }

                pickedLatLng = latLng
                pickedName = place.name ?: "新景點"
                pickedPlaceId = place.id //

                tvPickedName.text = "已選：$pickedName"

                pickedMarker?.remove()
                pickedMarker = map.addMarker(MarkerOptions().position(latLng).title(pickedName))
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))

                btnConfirm.isEnabled = true
            }

            override fun onError(status: com.google.android.gms.common.api.Status) {
                Toast.makeText(
                    this@PickLocationNewActivity,
                    "搜尋失敗：${status.statusMessage ?: "請改用地圖點選"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }
}
