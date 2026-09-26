package com.google.android.diskusage.utils

import android.app.Activity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Since targetSdk 35 the window is always laid out edge-to-edge, and the content
 * is drawn below the system bars. Keeps the content out of them.
 */
fun Activity.applySystemBarsPadding() {
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}
