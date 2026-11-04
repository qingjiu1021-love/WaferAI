package com.example.waferai

import android.app.Application

class WaferAiApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
