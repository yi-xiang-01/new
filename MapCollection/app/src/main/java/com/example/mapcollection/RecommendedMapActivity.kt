package com.example.mapcollection

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.mapcollection.network.ApiClient
import com.example.mapcollection.network.SpotRes
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class RecommendedMapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var m: GoogleMap
    private var postId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recommended_map)

        postId = intent.getStringExtra("POST_ID")

        (supportFragmentManager.findFragmentById(R.id.mapView) as SupportMapFragment)
            .getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        m = googleMap

        val tw = LatLng(23.6978, 120.9605)
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(tw, 7f))

        val id = postId ?: return

        lifecycleScope.launch {
            try {
                val spots: List<SpotRes> = ApiClient.api.getSpots(postId = id)

                var first: LatLng? = null
                spots.forEach { s ->
                    val p = LatLng(s.lat, s.lng)
                    if (first == null) first = p
                    m.addMarker(
                        MarkerOptions()
                            .position(p)
                            .title(s.name.ifBlank { "未命名景點" })
                    )
                }

                first?.let { m.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 13f)) }
            } catch (e: Exception) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    "載入推薦地圖失敗：${e.localizedMessage}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }
}
