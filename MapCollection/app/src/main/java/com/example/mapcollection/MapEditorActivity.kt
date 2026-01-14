package com.example.mapcollection

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.mapcollection.network.ApiClient
import com.example.mapcollection.network.CreatePostReq
import com.example.mapcollection.network.CreateSpotReq
import com.example.mapcollection.network.PostDetailRes
import com.example.mapcollection.network.RecommendedPostRes
import com.example.mapcollection.network.SpotRes
import com.example.mapcollection.network.UpdatePostReq
import com.example.mapcollection.network.UpdateSpotReq
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Spot 資料（前端用） */
data class RecSpot(
    val id: String = "",
    val name: String = "",
    val lat: Double = .0,
    val lng: Double = .0,
    val description: String = "",
    val photoUrl: String? = null
)

class MapEditorActivity : AppCompatActivity(), OnMapReadyCallback {

    // ✅ trips 暫時沿用 Firestore（跟 PublicMapViewerActivity 一樣）
    private val db = Firebase.firestore

    private var currentEmail: String? = null
    private var postId: String? = null           // 目前編輯的貼文
    private var recommendedMode = false          // FAB 進來 = false；舊入口（唯一推薦）= true

    // UI - Header
    private lateinit var edName: EditText
    private lateinit var edType: EditText
    private lateinit var btnAdd: Button
    private lateinit var btnDelete: Button
    private lateinit var btnSave: Button

    // Map
    private lateinit var gmap: GoogleMap
    private var mapReady = false
    private var selectedSpot: RecSpot? = null

    // BottomSheet
    private lateinit var sheetBehavior: BottomSheetBehavior<android.view.View>
    private lateinit var tvSpotTitle: TextView
    private lateinit var tvSpotDesc: TextView
    private lateinit var ivSpotPhoto: ImageView
    private lateinit var btnAddToTrip: Button
    private lateinit var btnEditSpot: Button
    private lateinit var btnDeleteSpot: Button

    // 換照片
    private var pendingPhotoSpot: RecSpot? = null
    private val spotPhotoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val p = pendingPhotoSpot
        val id = postId
        if (uri != null && p != null && id != null) {
            uploadSpotPhotoViaBackend(id, p, uri)
        }
        pendingPhotoSpot = null
    }

    /** 接收 NewPointActivity 回傳的新景點 */
    private val newSpotLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val id = postId

        if (res.resultCode != RESULT_OK) return@registerForActivityResult
        if (id.isNullOrBlank()) {
            Snackbar.make(btnSave, "尚未建立地圖（postId 為空）", Snackbar.LENGTH_LONG).show()
            return@registerForActivityResult
        }

        val data = res.data ?: return@registerForActivityResult
        val name = data.getStringExtra("spotName").orEmpty().trim()
        val desc = data.getStringExtra("spotDescription").orEmpty().trim()
        val lat = data.getDoubleExtra("latitude", Double.NaN)
        val lng = data.getDoubleExtra("longitude", Double.NaN)

        // ✅ 前端先檢查參數
        val email = currentEmail?.trim().orEmpty()
        if (email.isBlank()) {
            Snackbar.make(btnSave, "登入資訊遺失（email 為空），請重新登入", Snackbar.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        if (name.isBlank()) {
            Snackbar.make(btnSave, "景點名稱不能空白", Snackbar.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        if (lat.isNaN() || lng.isNaN()) {
            Snackbar.make(btnSave, "座標回傳錯誤（lat/lng 無效）", Snackbar.LENGTH_LONG).show()
            return@registerForActivityResult
        }

        lifecycleScope.launch {
            try {
                ApiClient.api.createSpot(
                    postId = id,
                    req = CreateSpotReq(
                        email = email,
                        name = name,
                        description = desc,
                        lat = lat,
                        lng = lng
                    )
                )

                Snackbar.make(btnSave, "已新增：$name", Snackbar.LENGTH_SHORT).show()
                fetchAndRenderSpots()

            } catch (e: HttpException) {
                val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                Snackbar.make(
                    btnSave,
                    "新增失敗 HTTP ${e.code()}：${body ?: e.message()}",
                    Snackbar.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                Snackbar.make(btnSave, "新增失敗：${e.localizedMessage}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ 先從 SharedPreferences 拿 email
        val sp = getSharedPreferences("Account", MODE_PRIVATE)
        currentEmail = sp.getString("LOGGED_IN_EMAIL", null)

        // ✅ 若本地沒有 email → 用 FirebaseAuth 補上
        if (currentEmail.isNullOrBlank()) {
            val em = Firebase.auth.currentUser?.email
            if (!em.isNullOrBlank()) {
                currentEmail = em
                sp.edit().putString("LOGGED_IN_EMAIL", em).apply()
            }
        }

        // ✅ 仍然拿不到就回登入
        if (currentEmail.isNullOrBlank()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_map_editor)

        // Header
        edName = findViewById(R.id.edMapName)
        edType = findViewById(R.id.edMapType)
        btnAdd = findViewById(R.id.btnAddSpot)
        btnDelete = findViewById(R.id.btnDeleteMap)
        btnSave = findViewById(R.id.btnSaveMap)

        // BottomSheet init
        val sheet = findViewById<android.view.View>(R.id.spotSheet)
        sheetBehavior = BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_HIDDEN
        }
        tvSpotTitle = findViewById(R.id.tvSpotTitle)
        tvSpotDesc = findViewById(R.id.tvSpotDesc)
        ivSpotPhoto = findViewById(R.id.ivSpotPhoto)
        btnAddToTrip = findViewById(R.id.btnAddToTrip)
        btnEditSpot = findViewById(R.id.btnEditSpot)
        btnDeleteSpot = findViewById(R.id.btnDeleteSpot)

        // ✅ 這裡改成真的加入行程
        btnAddToTrip.setOnClickListener { selectedSpot?.let { addToTripFlow(it) } }
        btnEditSpot.setOnClickListener { selectedSpot?.let { editSpotDialog(it) } }
        btnDeleteSpot.setOnClickListener { selectedSpot?.let { confirmDeleteSpot(it) } }

        // Map init
        val mf = supportFragmentManager.findFragmentById(R.id.mapPreview) as SupportMapFragment
        mf.getMapAsync(this)

        // 取得模式
        postId = intent.getStringExtra("POST_ID")
        val isNewPost = intent.getBooleanExtra("NEW_POST", false)

        lifecycleScope.launch {
            // 1) 先載入貼文基本資訊（名稱/種類）
            if (postId != null) {
                loadPostDetailAndInit(postId!!)
            } else {
                if (isNewPost) {
                    recommendedMode = false
                    edName.hint = "地圖名稱"
                    edType.hint = "地圖種類"
                    edName.setText("")
                    edType.setText("")
                } else {
                    // 舊入口：唯一推薦地圖
                    recommendedMode = true
                    loadOrPrepareRecommendedMapViaBackend()
                }
            }

            // 2) 若 map ready + postId 有了 → 拉 spots 畫圖
            if (mapReady && postId != null) fetchAndRenderSpots()
        }

        // 動作
        btnSave.setOnClickListener { saveMapMetaViaBackend() }

        btnAdd.setOnClickListener {
            ensureMapExistsViaBackend {
                val i = Intent(this, NewPointActivity::class.java)
                i.putExtra("POST_ID", postId)
                newSpotLauncher.launch(i)
            }
        }

        btnDelete.setOnClickListener { confirmDeleteMapViaBackend() }
    }

    // ---------------- Map ----------------

    override fun onMapReady(map: GoogleMap) {
        gmap = map
        gmap.moveCamera(CameraUpdateFactory.newLatLngZoom(LAT_TW, 7f))
        mapReady = true

        gmap.setOnMarkerClickListener { marker ->
            val tag = marker.tag
            if (tag !is RecSpot) return@setOnMarkerClickListener false
            selectedSpot = tag
            showSpotSheet(tag)
            true
        }
        gmap.setOnMapClickListener {
            sheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            selectedSpot = null
        }

        // map ready 後如果 postId 已存在 → 拉 spots
        if (postId != null) {
            lifecycleScope.launch { fetchAndRenderSpots() }
        }
    }

    private suspend fun fetchAndRenderSpots() {
        val id = postId ?: return
        if (!mapReady) return

        try {
            val list: List<SpotRes> = ApiClient.api.getSpots(postId = id)

            gmap.clear()
            selectedSpot = null
            sheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

            if (list.isEmpty()) return

            val b = LatLngBounds.Builder()
            var hasAny = false

            list.forEach { d ->
                val s = RecSpot(
                    id = d.id,
                    name = d.name,
                    lat = d.lat,
                    lng = d.lng,
                    description = d.description,
                    photoUrl = d.photoUrl
                )
                val p = LatLng(s.lat, s.lng)
                val m = gmap.addMarker(
                    MarkerOptions().position(p).title(if (s.name.isBlank()) "未命名景點" else s.name)
                )
                m?.tag = s
                b.include(p)
                hasAny = true
            }

            if (hasAny) {
                try {
                    gmap.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 64))
                } catch (_: Exception) {
                    val c = b.build().center
                    gmap.animateCamera(CameraUpdateFactory.newLatLngZoom(c, 15f))
                }
            }
        } catch (e: Exception) {
            Snackbar.make(btnSave, "載入景點失敗：${e.localizedMessage}", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showSpotSheet(s: RecSpot) {
        tvSpotTitle.text = if (s.name.isBlank()) "未命名景點" else s.name
        tvSpotDesc.text = if (s.description.isBlank()) "（尚無介紹）" else s.description
        if (s.photoUrl.isNullOrBlank()) {
            ivSpotPhoto.setImageResource(R.drawable.map)
        } else {
            Glide.with(this).load(s.photoUrl).into(ivSpotPhoto)
        }
        sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    // ---------------- 後端：貼文載入 / 建立 / 儲存 / 刪除 ----------------

    private suspend fun loadPostDetailAndInit(id: String) {
        try {
            val p: PostDetailRes = ApiClient.api.getPostDetail(id)
            edName.hint = "地圖名稱"
            edType.hint = "地圖種類"
            edName.setText(p.mapName)
            edType.setText(p.mapType)
        } catch (e: Exception) {
            Snackbar.make(btnSave, "讀取地圖資訊失敗：${e.localizedMessage}", Snackbar.LENGTH_SHORT).show()
        }
    }

    private suspend fun loadOrPrepareRecommendedMapViaBackend() {
        val email = currentEmail ?: return

        edName.hint = "地圖名稱"
        edType.hint = "地圖種類"

        try {
            val res: RecommendedPostRes? = ApiClient.api.getMyRecommendedPost(email)
            if (res != null && !res.id.isNullOrBlank()) {
                postId = res.id
                edName.setText(res.mapName.orEmpty())
                edType.setText(res.mapType.orEmpty())
            } else {
                edName.setText("")
                edType.setText("")
            }
        } catch (_: Exception) {
            edName.setText("")
            edType.setText("")
        }
    }

    /** 沒有地圖就建立；名稱/種類必填（依模式決定 isRecommended） */
    private fun ensureMapExistsViaBackend(onReady: () -> Unit) {
        if (postId != null) { onReady(); return }
        val email = currentEmail ?: return

        val name = edName.text?.toString()?.trim().orEmpty()
        val type = edType.text?.toString()?.trim().orEmpty()
        if (name.isEmpty() || type.isEmpty()) {
            Snackbar.make(btnAdd, "請先輸入地圖名稱與種類", Snackbar.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val created = ApiClient.api.createMyPost(
                    CreatePostReq(
                        email = email,
                        mapName = name,
                        mapType = type,
                        isRecommended = recommendedMode
                    )
                )
                postId = created.id
                Snackbar.make(btnSave, "已建立貼文", Snackbar.LENGTH_SHORT).show()
                if (mapReady) fetchAndRenderSpots()
                onReady()
            } catch (e: HttpException) {
                val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                Snackbar.make(btnSave, "建立貼文失敗 HTTP ${e.code()}：${body ?: e.message()}", Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                Snackbar.make(btnSave, "建立貼文失敗：${e.localizedMessage}", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveMapMetaViaBackend() {
        ensureMapExistsViaBackend {
            val id = postId ?: return@ensureMapExistsViaBackend
            val email = currentEmail ?: return@ensureMapExistsViaBackend

            lifecycleScope.launch {
                try {
                    ApiClient.api.updateMyPost(
                        postId = id,
                        req = UpdatePostReq(
                            email = email,
                            mapName = edName.text.toString(),
                            mapType = edType.text.toString()
                        )
                    )

                    Snackbar.make(btnSave, "已儲存", Snackbar.LENGTH_SHORT)
                        .addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                                finish()
                            }
                        })
                        .show()

                    setResult(RESULT_OK, Intent().putExtra("UPDATED_POST_ID", id))
                } catch (e: HttpException) {
                    val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                    Snackbar.make(btnSave, "儲存失敗 HTTP ${e.code()}：${body ?: e.message()}", Snackbar.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Snackbar.make(btnSave, "儲存失敗：${e.localizedMessage}", Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmDeleteMapViaBackend() {
        val id = postId ?: run {
            Snackbar.make(btnSave, "尚未建立地圖", Snackbar.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("刪除地圖")
            .setMessage("確定刪除此地圖與其所有景點？")
            .setPositiveButton("刪除") { _, _ -> deleteMapDeepViaBackend(id) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteMapDeepViaBackend(id: String) {
        val email = currentEmail ?: return
        lifecycleScope.launch {
            try {
                ApiClient.api.deleteMyPost(postId = id, email = email)
                Snackbar.make(btnSave, "已刪除", Snackbar.LENGTH_SHORT).show()
                finish()
            } catch (e: HttpException) {
                val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                Snackbar.make(btnSave, "刪除失敗 HTTP ${e.code()}：${body ?: e.message()}", Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                Snackbar.make(btnSave, "刪除失敗：${e.localizedMessage}", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- 後端：Spot 編輯/刪除/上傳照片 ----------------

    private fun confirmDeleteSpot(s: RecSpot) {
        val id = postId ?: return
        val email = currentEmail ?: return
        AlertDialog.Builder(this)
            .setTitle("刪除此景點")
            .setMessage("確定刪除「${if (s.name.isBlank()) "未命名景點" else s.name}」？")
            .setPositiveButton("刪除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        ApiClient.api.deleteSpot(postId = id, spotId = s.id, email = email)
                        Snackbar.make(btnDeleteSpot, "已刪除", Snackbar.LENGTH_SHORT).show()
                        sheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                        fetchAndRenderSpots()
                    } catch (e: HttpException) {
                        val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                        Snackbar.make(btnDeleteSpot, "刪除失敗 HTTP ${e.code()}：${body ?: e.message()}", Snackbar.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Snackbar.make(btnDeleteSpot, "刪除失敗：${e.localizedMessage}", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 編輯景點（名稱／介紹 + 更換照片） */
    private fun editSpotDialog(s: RecSpot) {
        val ctx = this
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }

        val edName = android.widget.EditText(ctx).apply {
            hint = "景點名稱"
            setText(s.name)
        }
        val edDesc = android.widget.EditText(ctx).apply {
            hint = "景點介紹"
            setText(s.description)
            minLines = 3
            maxLines = 6
            setHorizontallyScrolling(false)
        }
        val btnPick = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "更換照片"
            setOnClickListener {
                pendingPhotoSpot = s
                spotPhotoPickerLauncher.launch("image/*")
            }
        }

        container.addView(edName)
        container.addView(edDesc)
        container.addView(btnPick)

        AlertDialog.Builder(ctx)
            .setTitle("編輯景點")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("儲存") { _, _ ->
                val id = postId ?: return@setPositiveButton
                val email = currentEmail ?: return@setPositiveButton

                val newName = edName.text.toString().trim()
                val newDesc = edDesc.text.toString().trim()

                lifecycleScope.launch {
                    try {
                        ApiClient.api.updateSpot(
                            postId = id,
                            spotId = s.id,
                            req = UpdateSpotReq(
                                email = email,
                                name = newName,
                                description = newDesc
                            )
                        )
                        Snackbar.make(btnEditSpot, "已更新景點資訊", Snackbar.LENGTH_SHORT).show()
                        fetchAndRenderSpots()
                    } catch (e: HttpException) {
                        val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                        Snackbar.make(btnEditSpot, "更新失敗 HTTP ${e.code()}：${body ?: e.message()}", Snackbar.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Snackbar.make(btnEditSpot, "更新失敗：${e.localizedMessage}", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    /** 上傳景點照片：Android -> Flask (multipart) -> Storage */
    private fun uploadSpotPhotoViaBackend(postId: String, s: RecSpot, uri: Uri) {
        val email = currentEmail ?: return
        val bytes = decodeAndCompress(uri) ?: run {
            Snackbar.make(btnEditSpot, "讀取圖片失敗", Snackbar.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val emailPart = email.toRequestBody("text/plain".toMediaType())
                val imgBody = bytes.toRequestBody("image/jpeg".toMediaType())
                val part = MultipartBody.Part.createFormData(
                    name = "photo",
                    filename = "spot_${System.currentTimeMillis()}.jpg",
                    body = imgBody
                )

                ApiClient.api.uploadSpotPhoto(
                    postId = postId,
                    spotId = s.id,
                    email = emailPart,
                    photo = part
                )

                Snackbar.make(btnEditSpot, "照片已更新", Snackbar.LENGTH_SHORT).show()
                fetchAndRenderSpots()
            } catch (e: HttpException) {
                val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                Snackbar.make(btnEditSpot, "上傳失敗 HTTP ${e.code()}：${body ?: e.message()}", Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                Snackbar.make(btnEditSpot, "上傳失敗：${e.localizedMessage}", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    /** 把 Uri 讀成 Bitmap，最長邊縮到 1280，再壓成 JPEG 85% */
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

    // ---------------- ✅ 加入行程（Firestore，跟 PublicMapViewerActivity 一樣） ----------------

    private fun addToTripFlow(s: RecSpot) {
        val me = currentEmail?.trim()
        if (me.isNullOrBlank()) {
            Snackbar.make(btnAddToTrip, "請先登入", Snackbar.LENGTH_SHORT).show()
            return
        }

        val trips = mutableListOf<Triple<String, String, Int>>() // tripId, title, days

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
                            addSpotToTrip(tripId, dayIdx + 1, s)
                        }
                        .show()
                }
                .show()
        }

        // 1) 自己建立的行程
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

                // 2) 共用行程（協作者）
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
                    .addOnFailureListener {
                        showTripDialog()
                    }
            }
            .addOnFailureListener {
                Snackbar.make(btnAddToTrip, "讀取行程失敗：${it.localizedMessage}", Snackbar.LENGTH_LONG).show()
            }
    }

    private fun addSpotToTrip(tripId: String, day: Int, s: RecSpot) {
        val data = hashMapOf(
            "name" to s.name.ifBlank { "未命名景點" },
            "lat" to s.lat,
            "lng" to s.lng,
            "description" to s.description,
            "photoUrl" to s.photoUrl,
            "createdAt" to FieldValue.serverTimestamp()
        )

        db.collection("trips").document(tripId)
            .collection("days").document(day.toString())
            .collection("stops")
            .add(data)
            .addOnSuccessListener {
                Snackbar.make(btnAddToTrip, "已加入 Day $day", Snackbar.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Snackbar.make(btnAddToTrip, "加入失敗：${it.localizedMessage}", Snackbar.LENGTH_LONG).show()
            }
    }

    companion object {
        private val LAT_TW = LatLng(23.6978, 120.9605)
    }
}
