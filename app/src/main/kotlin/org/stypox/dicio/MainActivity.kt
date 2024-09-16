package org.stypox.dicio

import android.content.Intent
import android.content.Intent.ACTION_ASSIST
import android.content.Intent.ACTION_VOICE_COMMAND
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.di.WakeDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.io.wake.WakeService
import org.stypox.dicio.io.wake.WakeState
import org.stypox.dicio.ui.nav.Navigation
import org.stypox.dicio.util.BaseActivity
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : BaseActivity() {

    @Inject
    lateinit var skillEvaluator: SkillEvaluator
    @Inject
    lateinit var sttInputDevice: SttInputDeviceWrapper
    @Inject
    lateinit var wakeDevice: WakeDeviceWrapper

    private var wakeServiceJob: Job? = null

    private var nextAssistAllowed = Instant.MIN

    /**
     * Automatically loads the LLM and the STT when the [ACTION_ASSIST] intent is received. Applies
     * a backoff of [INTENT_BACKOFF_MILLIS], since during testing Android would send the assist
     * intent to the app twice in a row.
     */
    private fun onAssistIntentReceived() {
        // the wake word triggered notification is not needed anymore
        WakeService.cancelTriggeredNotification(this)

        val now = Instant.now()
        if (nextAssistAllowed < now) {
            nextAssistAllowed = now.plusMillis(INTENT_BACKOFF_MILLIS)
            Log.d(TAG, "Received assist intent")
            sttInputDevice.tryLoad(skillEvaluator::processInputEvent)
        } else {
            Log.w(TAG, "Ignoring duplicate assist intent")
        }
    }

    private fun turnOnScreenIfNeeded(intent: Intent?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
            intent?.action == ACTION_WAKE_WORD
        ) {
            // Dicio was started anew based on a wake word,
            // turn on the screen to let the user see what is happening
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        turnOnScreenIfNeeded(intent)
        if (isAssistIntent(intent)) {
            onAssistIntentReceived()
        }
    }

    override fun onStart() {
        isActivityRunning += 1
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
        isActivityRunning -= 1

        // once the activity is swiped away from the lock screen (or put in the background in any
        // other way), we don't want to show it on the lock screen anymore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TODO request notifications and microphone permission
        // TODO train "Hey Dicio" wake word
        // TODO also use "Display over other apps" permission?
        // TODO remove unneeded native architectures included in the APK

        turnOnScreenIfNeeded(intent)
        if (isAssistIntent(intent)) {
            onAssistIntentReceived()
        } else {
            // load the input device, without starting to listen
            sttInputDevice.tryLoad(null)
        }

        WakeService.start(this)
        wakeServiceJob?.cancel()
        wakeServiceJob = lifecycleScope.launch {
            wakeDevice.state
                .filter { it == WakeState.NotLoaded }
                .collect { WakeService.start(this@MainActivity) }
        }

        composeSetContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(
                    modifier = Modifier.safeDrawingPadding()
                ) {
                    Navigation()
                }
            }
        }
    }

    companion object {
        private const val INTENT_BACKOFF_MILLIS = 100L
        private val TAG = MainActivity::class.simpleName
        const val ACTION_WAKE_WORD = "org.stypox.dicio.MainActivity.ACTION_WAKE_WORD"

        var isActivityRunning: Int = 0
            private set

        private fun isAssistIntent(intent: Intent?): Boolean {
            return when (intent?.action) {
                ACTION_ASSIST, ACTION_VOICE_COMMAND, ACTION_WAKE_WORD -> true
                else -> false
            }
        }
    }
}
