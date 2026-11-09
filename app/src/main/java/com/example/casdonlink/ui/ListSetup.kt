package com.example.casdonlink.ui

import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.casdonlink.R

/**
 * 列表统一配置：纵向布局 + 1dp 分隔线，分隔线外观由 `divider_list` 维护。
 * RecyclerView 不读取布局里的 `android:divider`，所以分隔线必须在代码里挂装饰器。
 */
fun RecyclerView.configureAsList() {
    layoutManager = LinearLayoutManager(context)
    val decoration = DividerItemDecoration(context, DividerItemDecoration.VERTICAL)
    context.getDrawable(R.drawable.divider_list)?.let(decoration::setDrawable)
    addItemDecoration(decoration)
}
