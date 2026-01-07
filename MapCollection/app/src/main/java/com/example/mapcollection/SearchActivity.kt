package com.example.mapcollection

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mapcollection.model.SearchItem
import com.example.mapcollection.network.ApiClient
import com.example.mapcollection.network.SearchPostRes
import com.example.mapcollection.ui.search.SearchAdapter
import kotlinx.coroutines.launch
import java.util.Locale

class SearchActivity : AppCompatActivity() {

    private lateinit var etQuery: EditText
    private lateinit var rvResults: RecyclerView
    private lateinit var adapter: SearchAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        etQuery = findViewById(R.id.etQuery)
        rvResults = findViewById(R.id.rvResults)

        // 點擊結果 → 開唯讀瀏覽頁
        adapter = SearchAdapter { item ->
            startActivity(
                Intent(this, PublicMapViewerActivity::class.java)
                    .putExtra("POST_ID", item.id)
                    .putExtra("MAP_TITLE", item.title)
                    .putExtra("MAP_TYPE", item.subtitle.removePrefix("分類："))
            )
        }
        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = adapter

        // 底部導航
        findViewById<ImageButton>(R.id.btnRecommend)?.setOnClickListener {
            startActivity(Intent(this, RecommendActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnSearch)?.setOnClickListener { /* already here */ }
        findViewById<ImageButton>(R.id.btnPath)?.setOnClickListener {
            startActivity(Intent(this, PathActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnProfile)?.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        // 鍵盤搜尋鍵
        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE
            ) {
                performSearch(etQuery.text.toString())
                true
            } else false
        }
    }

    /**
     * 規則說明（保留你原本邏輯）：
     * - 若查詢字串 q 含「地圖」：
     *    1) 完整命中 q（名稱>分類）優先
     *    2) 其次：僅命中「地圖」者
     *    3) 其他全部排除
     * - 若 q 不含「地圖」：
     *    1) 必須完整命中 q（名稱或分類）才收
     *    2) 名稱命中優於分類命中
     *    3) 同分：命中位置越前越優，最後以 createdAt 新→舊
     */
    private fun performSearch(rawQuery: String) {
        val q = rawQuery.trim()
        if (q.isEmpty()) {
            adapter.submitList(emptyList())
            return
        }

        lifecycleScope.launch {
            try {
                val rows: List<SearchPostRes> = ApiClient.api.searchPosts(q = q, limit = 300)

                data class Weighted(
                    val item: SearchItem,
                    val score: Int,
                    val posBoost: Int,
                    val createdAtMillis: Long
                )

                val qL = q.lowercase(Locale.getDefault())
                val qContainsMapWord = qL.contains("地圖")

                val results = rows.mapNotNull { r ->
                    val name = r.mapName ?: ""
                    val type = r.mapType ?: ""

                    val nameL = name.lowercase(Locale.getDefault())
                    val typeL = type.lowercase(Locale.getDefault())

                    val fullHitName = nameL.contains(qL)
                    val fullHitType = typeL.contains(qL)
                    val hasFull = fullHitName || fullHitType

                    val containsMapInItem = nameL.contains("地圖") || typeL.contains("地圖")
                    val mapOnlyMatch = qContainsMapWord && !hasFull && containsMapInItem

                    // 篩選：符合兩種情況其中之一
                    if (!hasFull && !(qContainsMapWord && mapOnlyMatch)) {
                        return@mapNotNull null
                    }

                    // 打分
                    var score = 0
                    var posBoost = 0

                    // 完整命中優先 (名稱 > 分類)
                    if (fullHitName) {
                        score += 200
                        posBoost += 100 - nameL.indexOf(qL).coerceAtMost(100)
                    }
                    if (fullHitType) {
                        score += 180
                        posBoost += 60 - typeL.indexOf(qL).coerceAtMost(60)
                    }

                    // q 含「地圖」時，僅命中「地圖」屬於次優
                    if (mapOnlyMatch) {
                        val hitInName = nameL.contains("地圖")
                        val idx = if (hitInName) nameL.indexOf("地圖") else typeL.indexOf("地圖")
                        score += if (hitInName) 60 else 50
                        posBoost += 20 - idx.coerceAtMost(20)
                    }

                    Weighted(
                        item = SearchItem(
                            id = r.id,
                            title = name.ifBlank { "(未命名地圖)" },
                            subtitle = "分類：${type.ifBlank { "未分類" }}"
                        ),
                        score = score,
                        posBoost = posBoost,
                        createdAtMillis = r.createdAtMillis
                    )
                }.sortedWith(
                    compareByDescending<Weighted> { it.score }
                        .thenByDescending { it.posBoost }
                        .thenByDescending { it.createdAtMillis }
                ).map { it.item }

                adapter.submitList(results)
            } catch (e: Exception) {
                adapter.submitList(emptyList())
            }
        }
    }
}
