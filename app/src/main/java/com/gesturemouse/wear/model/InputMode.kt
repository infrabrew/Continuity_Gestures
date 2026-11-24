package com.gesturemouse.wear.model

/**
 * Input operating modes switchable on the fly via UI, rotary quick settings, or palm-up gestures.
 */
enum class InputMode(
    val displayName: String,
    val shortName: String,
    val description: String
) {
    MOUSE(
        displayName = "Mouse",
        shortName = "Mouse",
        description = "Air cursor, pinch to click, hold to drag, flick to scroll"
    ),
    ARROW_KEYS(
        displayName = "Arrow Keys",
        shortName = "Arrows",
        description = "Flick for arrows, pinch for Enter, palm-up pinch for Esc"
    ),
    MEDIA_CONTROLS(
        displayName = "Media",
        shortName = "Media",
        description = "Pinch Play/Pause, flick tracks, twist wrist for volume"
    ),
    PRESENTER(
        displayName = "Presenter",
        shortName = "Slides",
        description = "Pinch next slide, palm-up previous, arm aims laser cursor"
    );

    fun next(): InputMode {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }

    fun previous(): InputMode {
        val values = entries
        return values[(ordinal - 1 + values.size) % values.size]
    }
}
