package com.ikites.gemmachat

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds, imports and downloads the Gemma model file (.task).
 *
 * The model is ~500 MB, so it is NOT bundled in the APK. It lives in
 * the app's private storage as `model.task`.
 */
class ModelStore(private val context: Context) {

    val internalFile: File get() = File(context.filesDir, MODEL_NAME)

    /** Place a model here with adb: /sdcard/Android/data/<pkg>/files/model.task */
    private val externalFile: File? get() = context.getExternalFilesDir(null)?.let { File(it, MODEL_NAME) }

    fun findModel(): File? = listOfNotNull(internalFile, externalFile)
        .firstOrNull { it.exists() && it.length() > MIN_SIZE }

    fun deleteModel() {
        internalFile.delete()
        externalFile?.delete()
    }

    /** Copy a model the user picked with the system file picker. */
    suspend fun importFromUri(uri: Uri, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Cannot open the selected file")
        copyToModel(input, total, onProgress)
    }

    /** Download a model from a URL. Gated Hugging Face repos need a read token. */
    suspend fun download(url: String, hfToken: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                if (hfToken.isNotBlank()) setRequestProperty("Authorization", "Bearer ${hfToken.trim()}")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val hint = when (code) {
                    401, 403 -> " — accept the Gemma licence on Hugging Face and enter a valid read token."
                    404 -> " — check the URL."
                    else -> ""
                }
                error("Download failed: HTTP $code$hint")
            }
            copyToModel(conn.inputStream, conn.contentLengthLong, onProgress)
        }

    private fun copyToModel(input: InputStream, total: Long, onProgress: (Float) -> Unit): File {
        val tmp = File(context.filesDir, "$MODEL_NAME.part")
        input.use { src ->
            tmp.outputStream().use { dst ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    dst.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 2_000_000) {
                        lastReport = done
                        onProgress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
        }
        if (tmp.length() < MIN_SIZE) {
            tmp.delete()
            error("File is too small to be a model (${tmp.length()} bytes)")
        }
        internalFile.delete()
        if (!tmp.renameTo(internalFile)) error("Could not save the model file")
        onProgress(1f)
        return internalFile
    }

    companion object {
        const val MODEL_NAME = "model.task"
        private const val MIN_SIZE = 10L * 1024 * 1024

        /** Gemma 3 1B instruction-tuned, int4 — the "light" Gemma for phones (~530 MB). */
        const val DEFAULT_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"
    }
}
