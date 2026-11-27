package dev.neffly.gesturelauncher.data

/**
 * What a touch on the home screen's lower area does.
 *
 * [GESTURES] draws a shape that is matched against the trained gestures. [PIE] opens a ring of
 * the user's pie apps around the finger; sliding toward one and lifting launches it.
 */
enum class LaunchStyle {
    GESTURES, PIE;

    companion object {
        val DEFAULT = GESTURES
        fun fromName(name: String?): LaunchStyle = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
