package com.tejerfacil.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import com.tejerfacil.app.input.VolumeKeys
import com.tejerfacil.app.ui.AppNavHost
import com.tejerfacil.app.ui.theme.TejerFacilTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer

    /** Cambia cada vez que llega un PDF desde otra app ("Abrir con…" / "Compartir"). */
    private val importRequests = MutableStateFlow(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = (application as TejerFacilApp).container
        // Si la Activity se recrea, el PDF recibido ya se procesó.
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            TejerFacilTheme {
                AppNavHost(container, importRequests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /** PDF abierto desde un administrador de archivos u otra app. */
    private fun handleIncoming(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        if (container.importer.start(uri)) {
            importRequests.value = System.nanoTime()
        } else {
            Toast.makeText(this, "Ya hay una importación en curso. Esperá a que termine.", Toast.LENGTH_LONG).show()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (VolumeKeys.active && (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                VolumeKeys.emit(if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
