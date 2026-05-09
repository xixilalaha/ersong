package com.example.notificationreader2

import android.graphics.drawable.Drawable

data class AppToggleItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val enabled: Boolean
)

