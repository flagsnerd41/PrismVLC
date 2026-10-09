package com.example.myvlc

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: colors follow the wallpaper (Android 12+)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
