package com.fixlocal.android

import android.app.Application
import com.fixlocal.android.llm.LlmEngineManager

class FixLocalApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Ensure singleton instances are ready
        LlmEngineManager.getInstance(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            System.gc()
        }
    }
}
