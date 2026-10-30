package com.ikites.gemmachat

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File

/**
 * Thin wrapper around MediaPipe LLM Inference.
 *
 * - [LlmInference] = the loaded model (heavy, load once).
 * - [LlmInferenceSession] = one conversation; it keeps the chat history (KV cache).
 */
class GemmaEngine(context: Context, modelFile: File) : AutoCloseable {

    private val llm: LlmInference = LlmInference.createFromOptions(
        context,
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(MAX_TOKENS) // prompt + history + answer must fit in this
            .build()
    )

    private var session: LlmInferenceSession = newSession()

    /** Tokens used so far in this conversation (rough budget tracking). */
    private var usedTokens = 0

    private fun newSession(): LlmInferenceSession = LlmInferenceSession.createFromOptions(
        llm,
        LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTopP(0.95f)
            .setTemperature(0.8f)
            .build()
    )

    fun resetConversation() {
        session.close()
        session = newSession()
        usedTokens = 0
    }

    /**
     * Send one user turn. [onPartial] receives text pieces as they stream in;
     * [onDone] fires once at the end with the full answer (or an error).
     */
    fun send(
        userText: String,
        onPartial: (String) -> Unit,
        onDone: (Throwable?) -> Unit,
    ) {
        // Gemma chat template. The session remembers earlier turns.
        val turn = "<start_of_turn>user\n$userText<end_of_turn>\n<start_of_turn>model\n"

        val needed = llm.sizeInTokens(turn) + RESERVED_FOR_ANSWER
        if (usedTokens + needed > MAX_TOKENS) {
            // Context is full: start a fresh conversation instead of crashing.
            resetConversation()
        }

        try {
            session.addQueryChunk(turn)
            val sb = StringBuilder()
            session.generateResponseAsync { partial, done ->
                sb.append(partial)
                onPartial(partial)
                if (done) {
                    usedTokens += llm.sizeInTokens(turn + sb.toString() + "<end_of_turn>\n")
                    onDone(null)
                }
            }
        } catch (t: Throwable) {
            onDone(t)
        }
    }

    fun cancel() {
        runCatching { session.cancelGenerateResponseAsync() }
    }

    override fun close() {
        runCatching { session.close() }
        runCatching { llm.close() }
    }

    companion object {
        const val MAX_TOKENS = 2048
        private const val RESERVED_FOR_ANSWER = 512
    }
}
