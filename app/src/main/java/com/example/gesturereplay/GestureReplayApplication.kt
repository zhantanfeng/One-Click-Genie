package com.example.gesturereplay

import android.app.Application

class GestureReplayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GestureController.initialize(this)
    }
}
