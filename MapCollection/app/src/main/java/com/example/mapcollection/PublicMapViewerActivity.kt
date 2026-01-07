package com.example.mapcollection

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.mapcollection.network.ApiClient
import com.example.mapcollection.network.ProfileRes
import com.example.mapcollection.network.PostDetailRes
import com.example.mapcollection.network.SpotRes
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.firestore
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import kotlinx.coroutines.launch

data class RecoSpot(
    val id: String = "",
    val name: String = "",
    val lat: Double = .0,
    val lng: Double = .0,
    val description: String = "",
    val photoUrl: String? = null,
    val createdAt: Timestamp? = null
)

class PublicMapViewerActivity : AppCompatActivity(), OnMapReadyCallback {

    // ⚠️ 暫時保留：收藏/追蹤/加入行程 仍用 Firestore（下一刀再切後端）
    private val db = Firebase.firestore

    private var postId: String? = null
    private var mapTitle: String? = null
    private var mapType: String? = null
    private var ownerEmail: String? = null
    private var myEmail: String? = null

    private lateinit var map: GoogleMap
    private lateinit var sheetBehavior: BottomSheetBehavior<android.view.View>

    // Header views
    private lateinit var tvHeaderTitle: TextView
    private lateinit var tvHeaderType: TextView
    private lateinit var tvHeaderAuthor: TextView
    private lateinit var btnFav: ToggleButton
    private lateinit var btnFollow: ToggleButton

    // sheet views
    private lateinit var iv: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvDesc: TextView
    private lateinit var btnAddToTrip: Button

    private var currentSpot: RecoSpot? = null
    private val markers = mutableListOf<Marker>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_public_map_viewer)

        myEmail = getSharedPreferences("Account", MODE_PRIVATE)
            .getString("LOGGED_IN_EMAIL", null)

        postId = intent.getStringExtra("POST_ID")
        mapTitle = intent.getStringExtra("MAP_TITLE")
        mapType = intent.getStringExtra("MAP_TYPE")
        ownerEmail = intent.getStringExtra("OWNER_EMAIL")

        // Header
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle)
        tvHeaderType = findViewById(R.id.tvHeaderType)
        tvHeaderAuthor = findViewById(R.id.tvHeaderAuthor)
        btnFav = findViewById(R.id.btnFav)
        btnFollow = findViewById(R.id.btnFollow)
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        tvHeaderTitle.text = mapTitle ?: "推薦地圖"
        tvHeaderType.text = mapType ?: ""

        // Map
        (supportFragmentManager.findFragmentById(R.id.mapPublic) as SupportMapFragment)
            .getMapAsync(this)

        // bottom sheet
        val sheet = findViewById<android.view.View>(R.id.spotSheet)
        sheetBehavior = BottomSheetBehavior.from(sheet).apply {
            isHideable = true
            state = BottomSheetBehavior.STATE_HIDDEN
        }
        iv = findViewById(R.id.ivSpotPhoto)
        tvTitle = findViewById(R.id.tvSpotTitle)
        tvDesc = findViewById(R.id.tvSpotDesc)
        btnAddToTrip = findViewById(R.id.btnAddToTrip)
        btnAddToTrip.setOnClickListener { pickTripAndDayThenAdd() }

        // ========= 收藏/追蹤（暫時保留 Firestore 版本） =========

        btnFav.setOnCheckedChangeListener { _, isChecked ->
            val me = myEmail ?: return@setOnCheckedChangeListener
            val id = postId ?: return@setOnCheckedChangeListener
            val ref = db.collection("users").document(me)
            if (isChecked) {
                ref.update("favorites", FieldValue.arrayUnion(id))
                    .addOnFailureListener { btnFav.isChecked = false }
            } else {
                ref.update("favorites", FieldValue.arrayRemove(id))
                    .addOnFailureListener { btnFav.isChecked = true }
            }
        }

        btnFollow.setOnCheckedChangeListener { _, isChecked ->
            val me = myEmail ?: return@setOnCheckedChangeListener
            val target = ownerEmail ?: return@setOnCheckedChangeListener
            if (me == target) {
                Toast.makeText(this, "不能追蹤自己", Toast.LENGTH_SHORT).show()
                btnFollow.isChecked = false
                return@setOnCheckedChangeListener
            }
            val ref = db.collection("users").document(me)
            if (isChecked) {
                ref.update("following", FieldValue.arrayUnion(target))
                    .addOnFailureListener { btnFollow.isChecked = false }
            } else {
                ref.update("following", FieldValue.arrayRemove(target))
                    .addOnFailureListener { btnFollow.isChecked = true }
            }
        }

        preloadFavFollowState()
    }

    private fun preloadFavFollowState() {
        val me = myEmail ?: return
        val id = postId ?: return
        val author = ownerEmail
        db.collection("users").document(me).get()
            .addOnSuccessListener { d ->
                val favs = d.get("favorites") as? List<String> ?: emptyList()
                btnFav.isChecked = favs.contains(id)
                if (author != null) {
                    val follows = d.get("following") as? List<String> ?: emptyList()
                    btnFollow.isChecked = follows.contains(author)
                }
            }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        val tw = LatLng(23.6978, 120.9605)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(tw, 7f))

        map.setOnMarkerClickListener { m ->
            val s = m.tag as? RecoSpot ?: return@setOnMarkerClickListener true
            currentSpot = s
            tvTitle.text = s.name.ifBlank { "未命名景點" }
            tvDesc.text = s.description.ifBlank { "${s.lat}, ${s.lng}" }
            if (!s.photoUrl.isNullOrEmpty()) {
                Glide.with(this).load(s.photoUrl).into(iv)
            } else {
                iv.setImageResource(R.drawable.map)
            }
            sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            true
        }

        // ✅ 前後端分離：改用 Flask API 載入作者 + spots
        lifecycleScope.launch {
            val id = postId ?: return@launch

            // 1) 如果沒有帶 ownerEmail，就先用後端查貼文取得
            if (ownerEmail.isNullOrBlank()) {
                try {
                    val p: PostDetailRes = ApiClient.api.getPostDetail(id)
                    ownerEmail = p.ownerEmail
                } catch (_: Exception) {}
            }

            // 2) 顯示作者名字（用後端 me/profile?email=xxx 取得 userName）
            ownerEmail?.let { loadAuthorNameViaBackend(it) }

            // 3) 載入 spots（用後端 posts/{postId}/spots）
            loadSpotsViaBackend(id)

            // 4) Firestore 的收藏/追蹤初始狀態（暫時先留）
            preloadFavFollowState()
        }
    }

    private suspend fun loadSpotsViaBackend(postId: String) {
        try {
            val list: List<SpotRes> = ApiClient.api.getSpots(postId)

            markers.forEach { it.remove() }
            markers.clear()

            var firstLatLng: LatLng? = null

            list.forEach { d ->
                val s = RecoSpot(
                    id = d.id,
                    name = d.name,
                    lat = d.lat,
                    lng = d.lng,
                    description = d.description,
                    photoUrl = d.photoUrl
                )
                val latLng = LatLng(s.lat, s.lng)
                if (firstLatLng == null) firstLatLng = latLng

                val m = map.addMarker(MarkerOptions().position(latLng).title(s.name))
                m?.tag = s
                if (m != null) markers.add(m)
            }

            firstLatLng?.let { map.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 12f)) }

        } catch (e: Exception) {
            Toast.makeText(this, "載入公開地圖景點失敗：${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun loadAuthorNameViaBackend(email: String) {
        try {
            val p: ProfileRes = ApiClient.api.getMyProfile(email)
            val name = p.userName.takeIf { it.isNotBlank() } ?: email
            tvHeaderAuthor.text = "作者：$name"
        } catch (_: Exception) {
            tvHeaderAuthor.text = "作者：$email"
        }
    }

    // ⚠️ 這段 trips 目前仍用 Firestore（等你 Trip 後端好了再切）
    private fun pickTripAndDayThenAdd() {
        val me = myEmail
        if (me == null) { Toast.makeText(this, "請先登入", Toast.LENGTH_SHORT).show(); return }
        val spot = currentSpot ?: return

        val trips = mutableListOf<Triple<String, String, Int>>() // id, title, days

        fun showTripDialog() {
            if (trips.isEmpty()) {
                AlertDialog.Builder(this)
                    .setMessage("你還沒有任何行程，是否前往建立？")
                    .setPositiveButton("去建立") { _, _ ->
                        startActivity(Intent(this, PathActivity::class.java))
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return
            }
            val items = trips.map { (_, title, days) -> "$title（$days 天）" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("選擇行程")
                .setItems(items) { _, idx ->
                    val (tripId, _, days) = trips[idx]
                    val nums = (1..days).map { "Day $it" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("放到哪一天？")
                        .setItems(nums) { _, dayIdx ->
                            addSpotToTrip(tripId, dayIdx + 1, spot)
                        }.show()
                }.show()
        }

        db.collection("trips").whereEqualTo("ownerEmail", me).get()
            .addOnSuccessListener { mine ->
                mine.forEach { d ->
                    trips.add(
                        Triple(
                            d.id,
                            d.getString("title") ?: "我的行程",
                            (d.getLong("days") ?: 7L).toInt().coerceIn(1, 7)
                        )
                    )
                }
                db.collection("trips").whereArrayContains("collaborators", me).get()
                    .addOnSuccessListener { shared ->
                        shared.forEach { d ->
                            trips.add(
                                Triple(
                                    d.id,
                                    d.getString("title") ?: "共用行程",
                                    (d.getLong("days") ?: 7L).toInt().coerceIn(1, 7)
                                )
                            )
                        }
                        showTripDialog()
                    }
            }
    }

    private fun addSpotToTrip(tripId: String, day: Int, s: RecoSpot) {
        val data = hashMapOf(
            "name" to s.name,
            "lat" to s.lat,
            "lng" to s.lng,
            "description" to s.description,
            "photoUrl" to s.photoUrl,
            "createdAt" to FieldValue.serverTimestamp()
        )
        db.collection("trips").document(tripId)
            .collection("days").document(day.toString())
            .collection("stops").add(data)
            .addOnSuccessListener {
                Toast.makeText(this, "已加入 Day $day", Toast.LENGTH_SHORT).show()
            }
    }
}
