package com.yfantasyxx.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 间距与圆角标尺。
 *
 * 原来的数值是散在各处的字面量：页面左右留白有 16 和 20 两种（分组标题用 20、
 * 卡片用 16，两者左边缘差 4dp，一眼能看出没对齐），列表项之间 4/8/12 混着来。
 * 这里定一条 4dp 的基准栅格，所有间距取它的整数倍，新写的界面直接引这里。
 */
object Spacing {
    /** 页面左右留白，也是所有卡片的水平外边距 */
    val Gutter = 16.dp

    /** 卡片内边距 */
    val CardPadding = 16.dp

    /** 分组标题到上一组内容之间 */
    val SectionGap = 12.dp

    /** 同一组内相邻卡片之间 */
    val ItemGap = 8.dp

    /** 图标与紧随其后的文字之间 */
    val IconGap = 12.dp

    val XS = 2.dp
    val S = 4.dp
    val M = 8.dp
    val L = 12.dp
    val XL = 16.dp
    val XXL = 24.dp
}

/** 圆角标尺：同一种「东西」只允许用一种圆角 */
object Radius {
    /** 卡片（含弹窗内容块） */
    val Card = 16.dp

    /** 卡片内部的小控件：步进器外壳、输入框、可点标签 */
    val Control = 12.dp

    /** 小图标徽章 */
    val Badge = 9.dp

    /** 胶囊（按百分比给，随高度自适应） */
    const val Pill = 50
}
