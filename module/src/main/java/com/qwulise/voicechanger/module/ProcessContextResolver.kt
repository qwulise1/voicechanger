package com.qwulise.voicechanger.module

import android.app.Application
import android.content.Context

object ProcessContextResolver {
    @Volatile
    private var attachedContext: Context? = null

    fun attach(context: Context?) {
        attachedContext = context?.applicationContext ?: context
    }

    fun resolve(): Context? {
        attachedContext?.let { return it }
        val application = try {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getMethod("currentApplication").invoke(null) as? Application
        } catch (_: Throwable) {
            null
        }
        return application?.applicationContext?.also(::attach)
    }
}
