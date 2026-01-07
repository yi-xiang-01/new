package com.example.mapcollection

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class TripStopAdapter(
    private val data: MutableList<TripStop>,
    private val onClick: (Int) -> Unit,
    private val onAskAi: (Int) -> Unit, // 保留不影響
    private val onDelete: (TripStop) -> Unit // ⭐ 刪除 callback
) : RecyclerView.Adapter<TripStopAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTime: TextView = v.findViewById(R.id.tvTime)
        val viewTimeline: View = v.findViewById(R.id.viewTimeline)
        val tvSpotName: TextView = v.findViewById(R.id.tvSpotName)
        val tvSpotSub: TextView = v.findViewById(R.id.tvSpotSub)
        val tvAISuggestion: TextView = v.findViewById(R.id.tvAISuggestion)

        // ⭐ 刪除鍵
        val btnDelete: View = v.findViewById(R.id.btnDeleteStop)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_spot, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val s = data[pos]

        // 時間（24 小時制）
        val start = s.startTime.ifBlank { "--:--" }
        val end = s.endTime.ifBlank { "--:--" }
        h.tvTime.text = "$start\n$end"

        // 名稱
        h.tvSpotName.text = s.name.ifBlank { "未命名景點" }

        // 副標
        h.tvSpotSub.text =
            s.description.takeIf { it.isNotBlank() }
                ?: "(${s.lat}, ${s.lng})"

        // AI 建議
        h.tvAISuggestion.text =
            s.aiSuggestion.ifBlank { "（尚無建議，稍後自動產生）" }

        // 左側時間線顏色（用餐橘 / 景點綠）
        val isFood = s.category.contains("用餐")
        val colorRes = if (isFood)
            android.R.color.holo_orange_dark
        else
            android.R.color.holo_green_dark

        h.viewTimeline.setBackgroundColor(
            ContextCompat.getColor(h.itemView.context, colorRes)
        )

        // 點整張卡片 → 開詳細頁
        h.itemView.setOnClickListener {
            onClick(pos)
        }

        // ⭐ 刪除單一景點（含確認）
        h.btnDelete.setOnClickListener {
            AlertDialog.Builder(h.itemView.context)
                .setTitle("刪除景點")
                .setMessage("確定要刪除「${s.name.ifBlank { "此景點" }}」嗎？")
                .setPositiveButton("刪除") { _, _ ->
                    onDelete(s)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    override fun getItemCount(): Int = data.size
}
