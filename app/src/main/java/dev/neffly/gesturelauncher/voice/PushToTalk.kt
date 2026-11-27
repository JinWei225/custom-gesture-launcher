package dev.neffly.gesturelauncher.voice

import android.Manifest
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.drawer.AppRepository
import dev.neffly.gesturelauncher.search.Commands
import dev.neffly.gesturelauncher.search.QuickSearchActivity
import dev.neffly.gesturelauncher.ui.overrideNextTransition

/**
 * The home screen's push-to-talk: [press] when the finger goes down and holds, [lift] when it comes
 * up. While it is held, [caption] shows what is being heard.
 *
 * What was said is then dealt with as [VoiceQuery] reads it: an app the speaker asked to open is
 * launched from here, with nothing in between, and anything else opens quick search with the words
 * in its field, where the action row and the matches are waiting to be picked.
 *
 * Construct it in the activity's onCreate: the permission request has to be registered before the
 * activity starts.
 */
class PushToTalk(private val activity: ComponentActivity, private val caption: TextView) {

    private val voice = VoiceInput(activity, object : VoiceInput.Listener {
        override fun onListening() = show(activity.getString(R.string.voice_listening))

        override fun onPartial(text: String) = show(text)

        override fun onResult(candidates: List<String>) {
            hide()
            val apps = AppRepository.cachedOrPrime(activity)
            when (val resolved = VoiceQuery.resolve(candidates, apps, Prefs.voiceWords(activity))) {
                is VoiceQuery.Resolution.Launch -> AppRepository.launch(activity, resolved.app)
                is VoiceQuery.Resolution.Perform -> Commands.open(activity, resolved.command)
                is VoiceQuery.Resolution.Search -> {
                    activity.startActivity(QuickSearchActivity.intent(activity, resolved.query))
                    activity.overrideNextTransition()
                }
            }
        }

        override fun onError(message: Int) {
            show(activity.getString(message))
            caption.removeCallbacks(hideCaption)
            caption.postDelayed(hideCaption, ERROR_CAPTION_MILLIS)
        }
    })

    private val requestMic =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The dialog took the touch, so there is no hold left to end: this session ends when
            // the speaker goes quiet instead.
            if (granted) start()
            else Toast.makeText(activity, R.string.voice_permission_denied, Toast.LENGTH_SHORT).show()
        }

    private var held = false

    private val hideCaption = Runnable { hide() }

    fun press() {
        if (!Prefs.voiceEnabled(activity)) return
        // The last request is still being recognized, and its answer is on the way. Starting
        // another now would have the service refuse it as busy — on the same callbacks, which
        // would end the first one too and lose both.
        if (voice.active) return
        if (!VoiceInput.isAvailable(activity)) {
            Toast.makeText(activity, R.string.voice_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (!VoiceInput.hasPermission(activity)) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        held = true
        // Shown from the press, not from onListening: the recognizer takes a moment to open the
        // microphone, and the hold needs an answer the instant the haptic says it was taken.
        show(activity.getString(R.string.voice_listening))
        start()
    }

    private fun start() = voice.start(
        VoiceQuery.vocabulary(AppRepository.cachedOrPrime(activity), Prefs.voiceWords(activity))
    )

    /** The finger came up: whatever was said so far is the request. */
    fun lift() {
        if (!held) return
        held = false
        voice.stop()
    }

    /** The screen went away mid-session; nothing said is acted on. */
    fun cancel() {
        held = false
        voice.cancel()
        hide()
    }

    fun destroy() {
        caption.removeCallbacks(hideCaption)
        voice.destroy()
    }

    private fun show(text: String) {
        caption.removeCallbacks(hideCaption)
        caption.text = text
        caption.visibility = View.VISIBLE
    }

    private fun hide() {
        caption.visibility = View.GONE
    }

    private companion object {
        const val ERROR_CAPTION_MILLIS = 1800L
    }
}
