package com.dewijones92.totum.support

import android.app.Activity
import android.view.View
import android.view.ViewGroup

fun Activity.keepsScreenOn(): Boolean = findViewById<View>(android.R.id.content).anyKeepsScreenOn()

private fun View.anyKeepsScreenOn(): Boolean =
    keepScreenOn || (this is ViewGroup && (0 until childCount).any { getChildAt(it).anyKeepsScreenOn() })
