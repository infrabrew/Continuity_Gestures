package com.gesturemouse.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.gesturemouse.wear.GestureMouseApp
import com.gesturemouse.wear.MainActivity
import com.gesturemouse.wear.R

/**
 * Watch-face complication providing instant one-tap launch into GestureMouse directly
 * from any Wear OS watch face (`SHORT_TEXT`, `MONOCHROMATIC_IMAGE`, and `SMALL_IMAGE`).
 */
class QuickLaunchComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        buildComplicationData(type = type, shortLabel = "Gesture", tapAction = null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val app = application as GestureMouseApp
        val settings = app.settingsRepository.getSnapshot()
        val label = settings.lastConnectedName
            .takeIf { it.isNotBlank() }
            ?.take(7)
            ?: "Gesture"

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            if (settings.lastConnectedAddress.isNotBlank()) {
                putExtra(MainActivity.EXTRA_CONNECT_ADDRESS, settings.lastConnectedAddress)
            }
        }
        val tapIntent = PendingIntent.getActivity(
            this,
            request.complicationInstanceId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return buildComplicationData(
            type = request.complicationType,
            shortLabel = label,
            tapAction = tapIntent
        )
    }

    private fun buildComplicationData(
        type: ComplicationType,
        shortLabel: String,
        tapAction: PendingIntent?
    ): ComplicationData? {
        val contentDesc = PlainComplicationText.Builder("Launch Continuity Gestures").build()
        val icon = Icon.createWithResource(this, R.drawable.ic_ongoing_mouse)

        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(shortLabel).build(),
                contentDescription = contentDesc
            )
                .setMonochromaticImage(MonochromaticImage.Builder(icon).build())
                .setTapAction(tapAction)
                .build()

            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(
                monochromaticImage = MonochromaticImage.Builder(icon).build(),
                contentDescription = contentDesc
            )
                .setTapAction(tapAction)
                .build()

            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                smallImage = SmallImage.Builder(icon, SmallImageType.ICON).build(),
                contentDescription = contentDesc
            )
                .setTapAction(tapAction)
                .build()

            else -> null
        }
    }
}
