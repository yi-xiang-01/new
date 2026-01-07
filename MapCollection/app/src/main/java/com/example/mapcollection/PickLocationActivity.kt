package com.example.mapcollection

import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.mapcollection.network.AddTripStopReq
import com.example.mapcollection.network.ApiClient
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
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class PickLocationActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var map: GoogleMap
    private var pickedMarker: Marker? = null

    private var pickedLatLng: LatLng? = null
    private var pickedName: String = ""
    private var pickedPlaceId: String? = null

    private var startTime: String = "" // HH:mm
    private var endTime: String = ""   // HH:mm

    private var tripId: String? = null
    private var day: Int = 1
    private var myEmail: String? = null

    private lateinit var tvPickedName: TextView
    private lateinit var btnPickStart: Button
    private lateinit var btnPickEnd: Button
    private lateinit var btnConfirm: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pick_location)

        tripId = intent.getStringExtra("TRIP_ID")
        day = intent.getIntExtra("DAY", 1)
        myEmail = getSharedPreferences("Account", MODE_PRIVATE)
            .getString("LOGGED_IN_EMAIL", null)

        if (tripId.isNullOrBlank() || myEmail.isNullOrBlank()) {
            Toast.makeText(this, "參數錯誤，無法新增行程點", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvPickedName = findViewById(R.id.tvPickedName)
        btnPickStart = findViewById(R.id.btnPickStart)
        btnPickEnd = findViewById(R.id.btnPickEnd)
        btnConfirm = findViewById(R.id.btnConfirm)

        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
        btnConfirm.isEnabled = false

        // Places init
        if (!Places.isInitialized()) {
            val appInfo = packageManager.getApplicationInfo(
                packageName,
                PackageManager.GET_META_DATA
            )
            val apiKey = appInfo.metaData.getString("com.google.android.geo.API_KEY") ?: ""
            Places.initialize(applicationContext, apiKey, Locale.TAIWAN)
        }

        setupPlacesAutocomplete()

        val mapFrag = supportFragmentManager.findFragmentById(R.id.mapPick) as SupportMapFragment
        mapFrag.getMapAsync(this)

        // 選時間
        btnPickStart.setOnClickListener {
            pickTime(initial = startTime) { hhmm ->
                startTime = hhmm
                btnPickStart.text = "開始時間 $hhmm"
            }
        }
        btnPickEnd.setOnClickListener {
            pickTime(initial = endTime) { hhmm ->
                endTime = hhmm
                btnPickEnd.text = "結束時間 $hhmm"
            }
        }

        // 加入行程
        btnConfirm.setOnClickListener {
            val latLng = pickedLatLng ?: run {
                Toast.makeText(this, "請先選擇地點", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 若你想強制要選時間，就保留；不想強制就把這段刪掉
            if (startTime.isBlank() || endTime.isBlank()) {
                Toast.makeText(this, "請先選擇開始/結束時間", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val t = tripId ?: return@setOnClickListener
            val email = myEmail ?: return@setOnClickListener

            lifecycleScope.launch {
                try {
                    ApiClient.api.addTripStop(
                        tripId = t,
                        day = day,
                        email = email,
                        body = AddTripStopReq(
                            lat = latLng.latitude,
                            lng = latLng.longitude,
                            name = pickedName.ifBlank { "新景點" },
                            startTime = startTime,
                            endTime = endTime,
                            placeId = pickedPlaceId
                        )
                    )

                    Toast.makeText(
                        this@PickLocationActivity,
                        "已加入行程：${pickedName.ifBlank { "新景點" }}",
                        Toast.LENGTH_SHORT
                    ).show()

                    setResult(RESULT_OK)
                    finish()

                } catch (e: Exception) {
                    Toast.makeText(
                        this@PickLocationActivity,
                        "新增失敗：${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isMapToolbarEnabled = false

        map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(23.6978, 120.9605),
                7f
            )
        )

        // ✅ 只允許點 POI
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

        // ❌ 不允許點空白地圖
        map.setOnMapClickListener {
            Toast.makeText(this, "請點選地圖上的地點（POI）或使用上方搜尋列", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this@PickLocationActivity, "此地點沒有座標，請換一個", Toast.LENGTH_SHORT).show()
                    return
                }

                pickedLatLng = latLng
                pickedName = place.name ?: "新景點"
                pickedPlaceId = place.id

                tvPickedName.text = "已選：$pickedName"

                if (::map.isInitialized) {
                    pickedMarker?.remove()
                    pickedMarker = map.addMarker(
                        MarkerOptions().position(latLng).title(pickedName)
                    )
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
                }

                btnConfirm.isEnabled = true
            }

            override fun onError(status: com.google.android.gms.common.api.Status) {
                Toast.makeText(
                    this@PickLocationActivity,
                    "搜尋失敗：${status.statusMessage ?: "請改用地圖點選地點"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    private fun pickTime(initial: String, onPicked: (String) -> Unit) {
        val cal = Calendar.getInstance()
        var h = cal.get(Calendar.HOUR_OF_DAY)
        var m = cal.get(Calendar.MINUTE)

        if (initial.matches(Regex("\\d{2}:\\d{2}"))) {
            h = initial.substring(0, 2).toInt()
            m = initial.substring(3, 5).toInt()
        }

        TimePickerDialog(this, { _, hourOfDay, minute ->
            val hh = hourOfDay.toString().padStart(2, '0')
            val mm = minute.toString().padStart(2, '0')
            onPicked("$hh:$mm")
        }, h, m, true).show()
    }
}
