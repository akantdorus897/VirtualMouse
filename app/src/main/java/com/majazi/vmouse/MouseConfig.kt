package com.majazi.vmouse

import android.graphics.Color

/** Tanzimat-e mos ke bein-e MainActivity va Service eshterak mishavand */
object MouseConfig {

    // 1..20 → zareb-e sorat (0.2 ta 4.0)
    @Volatile
    var sensitivityLevel: Int = 8

    // 24..80 dp
    @Volatile
    var cursorSizeDp: Int = 34

    // 0=Tir 1=Dast 2=Markaz 3=Dayereh
    @Volatile
    var shape: Int = CursorView.SHAPE_ARROW

    // Rang-e neshangar
    @Volatile
    var color: Int = Color.WHITE

    // Kalibrasion (px offset baraye daghigh-shodan-e click)
    @Volatile
    var offsetX: Float = 0f

    @Volatile
    var offsetY: Float = 0f

    val multiplier: Float
        get() = sensitivityLevel / 5f
}
