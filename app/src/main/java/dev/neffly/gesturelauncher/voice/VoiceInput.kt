package dev.neffly.gesturelauncher.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import dev.neffly.gesturelauncher.R

/**
 * One utterance at a time through the device's own speech recognition service.
 *
 * The platform [SpeechRecognizer] rather than a bundled model: it adds nothing to the app, it is
 * the same engine the keyboard's mic key uses, and it decides for itself whether to work on the
 * device or online — this app has no internet permission either way, and only ever sees text.
 *
 * Push-to-talk and tap-to-talk are the same session: [start] opens it, and it ends either when the
 * caller says so with [stop] (the finger lifted) or on its own when the recognizer hears the
 * speaker go quiet. Whichever comes first produces the one [Listener.onResult] or
 * [Listener.onError].
 *
 * The recognizer is created on first use and held until [destroy]; it is bound to the system
 * service, so callers destroy it when their screen goes away.
 */
class VoiceInput(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** The microphone is open; anything said from here on is heard. */
        fun onListening() {}

        /** The recognizer's best guess so far, while the speaker is still talking. */
        fun onPartial(text: String) {}

        /** What was said: the recognizer's hypotheses, best first, never empty. */
        fun onResult(candidates: List<String>)

        fun onError(@StringRes message: Int)
    }

    private var recognizer: SpeechRecognizer? = null

    /** Between [start] and the session's result or error. */
    var active = false
        private set

    /**
     * Opens a session. [vocabulary] is words the speaker is likely to say that the recognizer would
     * not guess on its own — app names and aliases — and is used where the platform supports it.
     */
    fun start(vocabulary: List<String>) {
        val recognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(callbacks)
            recognizer = it
        }
        active = true
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                // Tuned for short queries rather than dictation, which is what every command is.
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Alternatives are what let an unusual app name be found when the best guess is
                // an ordinary word that sounds like it — see VoiceQuery.resolve.
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_CANDIDATES)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && vocabulary.isNotEmpty()) {
                        putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(vocabulary))
                    }
                }
        )
    }

    /** Stops listening and asks for the result of what has been said so far. */
    fun stop() {
        if (active) recognizer?.stopListening()
    }

    /** Drops the session without a result. */
    fun cancel() {
        if (!active) return
        active = false
        recognizer?.cancel()
    }

    fun destroy() {
        active = false
        recognizer?.destroy()
        recognizer = null
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (active) listener.onListening()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!active) return
            candidates(partialResults).firstOrNull()?.let(listener::onPartial)
        }

        override fun onResults(results: Bundle?) {
            if (!active) return
            active = false
            val candidates = candidates(results)
            if (candidates.isEmpty()) listener.onError(R.string.voice_no_match)
            else listener.onResult(candidates)
        }

        override fun onError(error: Int) {
            // A cancelled session can still report ERROR_CLIENT on its way out; nobody is waiting.
            if (!active) return
            active = false
            listener.onError(message(error))
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun candidates(bundle: Bundle?): List<String> =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
            .filter { it.isNotBlank() }

    companion object {

        private const val MAX_CANDIDATES = 5

        /** Whether the device has a speech recognition service at all; some ROMs ship none. */
        fun isAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        @StringRes
        private fun message(error: Int): Int = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_no_match
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> R.string.voice_error_network
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_permission_denied
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_error_busy
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> R.string.voice_error_language
            else -> R.string.voice_error_generic
        }

        // SpeechRecognizer.ERROR_LANGUAGE_* are API 31 names for codes an older service never
        // sends; spelled out so the mapping compiles against minSdk without an inlined-API warning.
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
