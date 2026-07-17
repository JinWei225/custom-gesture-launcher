package dev.neffly.gesturelauncher.crash

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import dev.neffly.gesturelauncher.MainActivity
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.drawer.AppDrawerActivity
import dev.neffly.gesturelauncher.settings.openDefaultLauncherSettings

/**
 * Shown instead of the gesture home screen after repeated crashes. Gives one-tap access to the app
 * drawer and deep-links to the system "default Home app" picker.
 *
 * Note: no app can force-unset itself as the default launcher (Android security). The best we can do
 * is take you straight to the picker.
 */
class SafeModeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_safe_mode)

        findViewById<Button>(R.id.openDrawerButton).setOnClickListener {
            startActivity(Intent(this, AppDrawerActivity::class.java))
        }

        findViewById<Button>(R.id.homeSettingsButton).setOnClickListener {
            openDefaultLauncherSettings()
        }

        findViewById<Button>(R.id.resetButton).setOnClickListener {
            Prefs.resetCrashCount(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
