package com.jollydoddger.waymark

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Hold-to-talk, ported from loose-ends: [SpeechRecognizer] rather than the
 * no-permission [RecognizerIntent] dialog, because hold-and-release needs
 * start and stop under this app's control. The finger coming off the button
 * is the send. The `<queries>` entry for android.speech.RecognitionService in
 * the manifest is load-bearing — without it the recogniser is invisible from
 * Android 11 and this reports "no recogniser" on a phone that plainly has one.
 *
 * Everything must be called on the main thread; SpeechRecognizer binds to a
 * remote service and silently does nothing otherwise.
 */
class Voice(private val context: Context) {

    var listening = false
        private set

    /** Called with the final text once the recogniser settles. */
    var onFinal: (String) -> Unit = {}

    /** Called on state changes so the mic button can show what's happening. */
    var onChange: (state: String) -> Unit = {}

    private var recognizer: SpeechRecognizer? = null

    fun available(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    fun start() {
        if (listening) return
        if (!available()) {
            onChange("No speech recogniser on this phone")
            return
        }
        listening = true
        onChange("Listening…")
        runCatching {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
                startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        // Held down: the finger, not a silence timer, ends the
                        // sentence. Recognisers honour this loosely; the real
                        // stop is stopListening() on release.
                        .putExtra(
                            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                            10_000L,
                        )
                        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
                )
            }
        }.onFailure {
            Log.e("Voice", "could not start listening", it)
            listening = false
            onChange("Couldn't start the microphone")
        }
    }

    /** Release: keep the words (stopListening), never cancel() them away. */
    fun stop() {
        if (!listening) return
        listening = false
        onChange("…")
        runCatching { recognizer?.stopListening() }
    }

    fun dispose() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
            listening = false
            val heard = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            onChange("")
            if (heard.isNotBlank()) onFinal(heard)
        }

        override fun onError(error: Int) {
            listening = false
            onChange(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "Didn't catch that"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                    else -> "Mic error $error"
                },
            )
        }
    }
}
