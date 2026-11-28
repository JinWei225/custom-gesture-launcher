package dev.neffly.gesturelauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.withStyledAttributes
import androidx.core.view.isVisible
import com.google.android.material.materialswitch.MaterialSwitch
import dev.neffly.gesturelauncher.R
import kotlin.math.roundToInt

/**
 * One row of the settings screen: a title, a subtitle, and for an on/off setting a switch.
 *
 * One view rather than a block of nested layouts per row, so the screen's layout is a list of
 * settings instead of twenty copies of the same markup, and the two things every row does the
 * same way live here once: dimming when it doesn't apply ([setEnabled]), and — for a switch row —
 * telling accessibility services that it is a switch and whether it is on, which the row, not
 * the decorative switch inside it, is the one to say.
 */
class SettingsRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    val title: TextView
    val subtitle: TextView
    private val switch: MaterialSwitch
    private val switchable: Boolean

    /** The switch's state. Meaningless for a row without one. */
    var isChecked: Boolean
        get() = switch.isChecked
        set(value) {
            switch.isChecked = value
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padH = dp(20)
        val padV = dp(14)
        setPadding(padH, padV, padH, padV)
        isClickable = true
        isFocusable = true
        val ripple = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        setBackgroundResource(ripple.resourceId)

        LayoutInflater.from(context).inflate(R.layout.view_settings_row, this)
        title = findViewById(R.id.settingsRowTitle)
        subtitle = findViewById(R.id.settingsRowSubtitle)
        switch = findViewById(R.id.settingsRowSwitch)

        var hasSwitch = false
        context.withStyledAttributes(attrs, R.styleable.SettingsRow) {
            title.text = getText(R.styleable.SettingsRow_title)
            subtitle.text = getText(R.styleable.SettingsRow_subtitle)
            if (hasValue(R.styleable.SettingsRow_subtitleMaxLines)) {
                subtitle.maxLines = getInt(R.styleable.SettingsRow_subtitleMaxLines, Int.MAX_VALUE)
            }
            hasSwitch = getBoolean(R.styleable.SettingsRow_switchable, false)
        }
        switchable = hasSwitch
        switch.isVisible = switchable
    }

    /** Both halves of "this row doesn't apply right now": it stops taking taps, and it dims. */
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else DISABLED_ALPHA
        // Called by View's constructor before the children exist.
        @Suppress("SENSELESS_COMPARISON")
        if (title == null) return
        title.isEnabled = enabled
        subtitle.isEnabled = enabled
        switch.isEnabled = enabled
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (!switchable) return
        info.className = Switch::class.java.name
        info.isCheckable = true
        info.isChecked = isChecked
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        /** Faded, not hidden: the row stays visible so it's clear what the setting above unlocks. */
        const val DISABLED_ALPHA = 0.4f
    }
}
