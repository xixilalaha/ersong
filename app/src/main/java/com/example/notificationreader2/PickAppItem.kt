package com.example.notificationreader2

import android.graphics.drawable.Drawable

data class PickAppItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable?
)

