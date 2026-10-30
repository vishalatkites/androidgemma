package com.ikites.gemmachat

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Keep the screen on so long downloads / generations are not interrupted.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App(vm: ChatViewModel = viewModel()) {
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (val s = vm.screen) {
            Screen.NeedModel -> SetupScreen(vm)
            is Screen.Transferring -> ProgressScreen(s.label, s.progress)
            Screen.Loading -> ProgressScreen("Loading Gemma into memory…", -1f)
            Screen.Chat -> ChatScreen(vm)
        }
        vm.error?.let { msg ->
            AlertDialog(
                onDismissRequest = vm::clearError,
                confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } },
                title = { Text("Something went wrong") },
                text = { Text(msg) },
            )
        }
    }
}

@Composable
private fun SetupScreen(vm: ChatViewModel) {
    var url by remember { mutableStateOf(ModelStore.DEFAULT_URL) }
    var token by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::import)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Gemma Chat", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Runs Gemma fully on your phone. No internet needed after setup. " +
                "First, give the app a Gemma model file (.task, about 500 MB).",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text("Option 1 — file already on the phone", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Pick .task model file")
        }

        HorizontalDivider()

        Text("Option 2 — download from Hugging Face", style = MaterialTheme.typography.titleMedium)
        Text(
            "Gemma is gated. Accept the licence on the model page, then create a read token at " +
                "huggingface.co/settings/tokens.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(url, { url = it }, label = { Text("Model URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            token, { token = it },
            label = { Text("Hugging Face token (hf_…)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.download(url, token) }, modifier = Modifier.fillMaxWidth()) {
            Text("Download model")
        }
    }
}

@Composable
private fun ProgressScreen(label: String, progress: Float) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        if (progress in 0f..1f) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("${(progress * 100).toInt()}%")
        } else {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ChatScreen(vm: ChatViewModel) {
    var input by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text?.length) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Gemma Chat", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::newChat) { Text("New chat") }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Remove model") }, onClick = { menu = false; vm.removeModel() })
                }
            }
        }
        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.messages.isEmpty()) item {
                Text(
                    "Ask anything. Everything runs on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
            items(vm.messages, key = { it.id }) { Bubble(it) }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            Spacer(Modifier.width(8.dp))
            if (vm.generating) {
                Button(onClick = vm::stop) { Text("Stop") }
            } else {
                Button(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank()) { Text("Send") }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val bg = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        Text(
            text = m.text.ifEmpty { "…" },
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(bg, RoundedCornerShape(14.dp))
                .padding(12.dp),
        )
    }
}
