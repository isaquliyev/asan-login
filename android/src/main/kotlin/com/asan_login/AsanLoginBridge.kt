package com.asan_login

import android.content.Intent

object AsanLoginBridge {
    var onNewIntent: ((Intent) -> Unit)? = null
        set(value) {
            field = value
            pendingIntent?.let { pending ->
                value?.invoke(pending)
                pendingIntent = null
            }
        }

    var pendingIntent: Intent? = null
}