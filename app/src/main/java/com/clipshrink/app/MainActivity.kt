package com.clipshrink.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: ConverterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        window.isNavigationBarContrastEnforced = false
        if (savedInstanceState == null) handle(intent)
        setContent {
            ClipShrinkTheme {
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> if (uri != null) vm.loadClip(uri) }
                ClipShrinkApp(
                    vm = vm,
                    onPick = { picker.launch(arrayOf("video/*")) },
                    onShare = { f -> shareFile(f) },
                    onShareOriginal = { u -> shareUri(u, contentResolver.getType(u) ?: "video/*") }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
        if (uri != null) vm.loadClip(uri)
    }

    private fun shareFile(f: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        shareUri(uri, "video/mp4")
    }

    private fun shareUri(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share clip"))
    }
}
