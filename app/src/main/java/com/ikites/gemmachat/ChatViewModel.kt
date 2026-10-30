package com.ikites.gemmachat

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ChatMessage(val id: Long, val fromUser: Boolean, val text: String)

sealed interface Screen {
    /** No model on the phone yet. */
    data object NeedModel : Screen
    /** Downloading or copying the model. progress < 0 means unknown size. */
    data class Transferring(val label: String, val progress: Float) : Screen
    /** Loading the model into memory. */
    data object Loading : Screen
    data object Chat : Screen
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app)
    private var engine: GemmaEngine? = null
    private var nextId = 0L

    var screen by mutableStateOf<Screen>(Screen.NeedModel)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var generating by mutableStateOf(false)
        private set
    val messages = mutableStateListOf<ChatMessage>()

    init {
        store.findModel()?.let { load(it) }
    }

    fun clearError() { error = null }

    fun download(url: String, token: String) = transfer("Downloading model") { progress ->
        store.download(url.trim(), token, progress)
    }

    fun import(uri: Uri) = transfer("Copying model") { progress ->
        store.importFromUri(uri, progress)
    }

    private fun transfer(label: String, block: suspend ((Float) -> Unit) -> File) {
        viewModelScope.launch {
            screen = Screen.Transferring(label, -1f)
            try {
                val file = block { p -> screen = Screen.Transferring(label, p) }
                load(file)
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
                screen = Screen.NeedModel
            }
        }
    }

    private fun load(file: File) {
        viewModelScope.launch {
            screen = Screen.Loading
            try {
                engine?.close()
                engine = withContext(Dispatchers.IO) { GemmaEngine(getApplication(), file) }
                screen = Screen.Chat
            } catch (t: Throwable) {
                error = "Could not load model: ${t.message}. The file may be corrupt or not a MediaPipe .task model."
                screen = Screen.NeedModel
            }
        }
    }

    fun send(text: String) {
        val e = engine ?: return
        if (text.isBlank() || generating) return
        messages += ChatMessage(nextId++, fromUser = true, text = text.trim())
        val reply = ChatMessage(nextId++, fromUser = false, text = "")
        messages += reply
        generating = true

        viewModelScope.launch(Dispatchers.IO) {
            e.send(
                userText = text.trim(),
                onPartial = { piece ->
                    viewModelScope.launch(Dispatchers.Main) { appendToLast(piece) }
                },
                onDone = { err ->
                    viewModelScope.launch(Dispatchers.Main) {
                        if (err != null) error = err.message ?: err.toString()
                        generating = false
                    }
                },
            )
        }
    }

    private fun appendToLast(piece: String) {
        val i = messages.lastIndex
        if (i >= 0 && !messages[i].fromUser) {
            messages[i] = messages[i].copy(text = messages[i].text + piece)
        }
    }

    fun stop() {
        engine?.cancel()
        generating = false
    }

    fun newChat() {
        if (generating) stop()
        messages.clear()
        viewModelScope.launch(Dispatchers.IO) { engine?.resetConversation() }
    }

    fun removeModel() {
        engine?.close()
        engine = null
        messages.clear()
        store.deleteModel()
        screen = Screen.NeedModel
    }

    override fun onCleared() {
        engine?.close()
    }
}
