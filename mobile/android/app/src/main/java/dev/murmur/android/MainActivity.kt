package dev.murmur.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.murmur.android.ui.MurmurApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MurmurViewModel by viewModels()
    private lateinit var platform: AndroidPlatform
    private var autoSync: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        platform = AndroidPlatform(this, viewModel)
        autoSync = AutoSync.install(viewModel.runtime, lifecycle)
        lifecycleScope.launch {
            viewModel.platformRequests.collect { platform.handle(it.action, it.payload) }
        }
        setContent {
            val state = viewModel.state.collectAsStateWithLifecycle().value
            MurmurApp(state) { action, payload ->
                if (!platform.handle(action, payload)) viewModel.dispatch(action, payload)
            }
        }
        platform.onIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Protect journal recovery before initialization wakes the queue. Do not resume it
        // here: recording recovery must finish before any queued work starts.
        val processor = viewModel.runtime.processor
        if (processor.hasWork()) processor.ensureService()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        platform.onIntent(intent)
    }

    override fun onDestroy() {
        platform.close()
        autoSync?.close()
        super.onDestroy()
    }
}
