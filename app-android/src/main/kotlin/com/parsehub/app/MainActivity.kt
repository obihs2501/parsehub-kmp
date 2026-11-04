package com.parsehub.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {

    private val sharedText = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedText.value = intentText(intent)
        setContent { App(sharedText) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val t = intentText(intent)
        if (t.isNotEmpty()) sharedText.value = t
    }

    private fun intentText(i: Intent?): String {
        if (i == null) return ""
        return when (i.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            Intent.ACTION_VIEW -> i.dataString ?: ""
            else -> ""
        }
    }
}
