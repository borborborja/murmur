package dev.murmur.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import org.json.JSONObject

class MurmurViewModel(application: Application) : AndroidViewModel(application) {
    val runtime = AppRuntime.get(application)
    val state = runtime.state
    val platformRequests = runtime.platformRequests
    fun dispatch(action: String, payload: JSONObject = JSONObject()) = runtime.dispatch(action, payload)
    fun clearMessage() = runtime.clearMessage()
}
