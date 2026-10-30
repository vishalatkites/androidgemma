# Gemma Chat (Android)

A simple chatbot that runs **Gemma 3 1B** fully on the phone, using Google's
MediaPipe LLM Inference (`com.google.mediapipe:tasks-genai`). No server, no API key.

## Get the APK

Every push to `main` runs GitHub Actions and:
- publishes a **Release** named `Gemma Chat build N` with the APK attached (easiest: open it on the phone), and
- uploads the APK as a workflow **artifact**.

Requires an **arm64** phone, Android 8.0+, and ~2 GB free RAM.

## Give the app a model

The model (~530 MB) is not inside the APK. The app accepts a MediaPipe **`.task`** file.

| Source | Works? |
|---|---|
| `gemma3-1b-it-int4.task` from [litert-community/Gemma3-1B-IT](https://huggingface.co/litert-community/Gemma3-1B-IT) | Yes |
| Ollama / LM Studio / llama.cpp files (`.gguf`, Ollama blobs) | No — different format |
| `.litertlm` files (LiteRT-LM) | No — needs the LiteRT-LM runtime |

Three ways to load it:
1. **Pick file** — copy the `.task` file to the phone (USB, Drive), tap *Pick .task model file*.
2. **Download** — accept the Gemma licence on Hugging Face, create a read token, paste it in the app.
3. **adb** — `adb push gemma3-1b-it-int4.task /sdcard/Android/data/com.ikites.gemmachat/files/model.task`

## Build locally

```
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

## Code map

- `ModelStore.kt` — find / import / download the model file
- `GemmaEngine.kt` — loads Gemma, keeps a chat session, streams tokens
- `ChatViewModel.kt` — app state
- `MainActivity.kt` — Compose UI (setup screen + chat screen)
