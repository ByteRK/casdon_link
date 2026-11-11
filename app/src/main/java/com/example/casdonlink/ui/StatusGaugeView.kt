package com.example.casdonlink.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.example.casdonlink.R
import com.google.android.material.progressindicator.CircularProgressIndicator

/**
 * 设备状态环形指标：显示使用率百分比、标签和一行容量说明。
 *
 * 只负责渲染，不做业务判断；缺项由 DeviceActivity 隐藏整个控件。
 */
class StatusGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val progress: CircularProgressIndicator
    private val value: TextView
    private val label: TextView
    private val detail: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_status_gauge, this, true)
        progress = findViewById(R.id.gaugeProgress)
        value = findViewById(R.id.gaugeValue)
        label = findViewById(R.id.gaugeLabel)
        detail = findViewById(R.id.gaugeDetail)
    }

    /**
     * @param percent 0-100 的使用率
     * @param detail 环下方的容量摘要，例如 `49 / 94 MB`
     * @param accentColor 环的颜色；正常为 primary/secondary，接近满载时由调用方换成 error
     */
    fun bind(labelText: String, percent: Int, detailText: String, accentColor: Int) {
        label.text = labelText
        value.text = "$percent%"
        detail.text = detailText
        progress.setIndicatorColor(accentColor)
        progress.setProgressCompat(percent.coerceIn(0, 100), false)
    }
}
