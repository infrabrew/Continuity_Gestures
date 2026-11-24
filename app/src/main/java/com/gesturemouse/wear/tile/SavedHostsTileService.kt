package com.gesturemouse.wear.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Chip
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.gesturemouse.wear.GestureMouseApp
import com.gesturemouse.wear.MainActivity
import com.gesturemouse.wear.model.SavedHost
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * Wear OS Tile displaying up to 3 saved GestureMouse Bluetooth HID hosts with one-tap
 * launch & auto-connect straight into mouse control.
 */
class SavedHostsTileService : TileService() {

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = tileScope.future {
        val app = application as GestureMouseApp
        val settings = app.settingsRepository.getSnapshot()
        val hosts = settings.savedHosts.take(3)

        val layout = buildTileLayout(
            context = this@SavedHostsTileService,
            requestParams = requestParams,
            hosts = hosts
        )

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(
                        LayoutElementBuilders.Layout.Builder()
                            .setRoot(layout)
                            .build()
                    )
                    .build()
            )
            .build()

        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline)
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> = tileScope.future {
        ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    private fun buildTileLayout(
        context: Context,
        requestParams: RequestBuilders.TileRequest,
        hosts: List<SavedHost>
    ): LayoutElementBuilders.LayoutElement {
        val deviceParams = requestParams.deviceConfiguration

        val columnBuilder = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        if (hosts.isEmpty()) {
            columnBuilder.addContent(
                Text.Builder(context, "No saved hosts yet")
                    .setTypography(Typography.TYPOGRAPHY_BODY2)
                    .setColor(argb(COLOR_TEXT_SECONDARY))
                    .build()
            )
        } else {
            hosts.forEachIndexed { index, host ->
                if (index > 0) {
                    columnBuilder.addContent(
                        LayoutElementBuilders.Spacer.Builder()
                            .setHeight(DimensionBuilders.dp(4f))
                            .build()
                    )
                }
                val chipColors = if (index == 0) {
                    ChipColors(
                        argb(COLOR_EMERALD_BG),
                        argb(COLOR_EMERALD_TEXT)
                    )
                } else {
                    ChipColors(
                        argb(COLOR_SURFACE_BG),
                        argb(COLOR_TEXT_PRIMARY)
                    )
                }
                columnBuilder.addContent(
                    Chip.Builder(context, createLaunchClickable(context, host.address), deviceParams)
                        .setPrimaryLabelContent(host.name)
                        .setSecondaryLabelContent("Tap to connect")
                        .setChipColors(chipColors)
                        .setWidth(DimensionBuilders.expand())
                        .build()
                )
            }
        }

        val primaryLabel = Text.Builder(context, "Continuity")
            .setTypography(Typography.TYPOGRAPHY_TITLE3)
            .setColor(argb(COLOR_EMERALD_TEXT))
            .build()

        val bottomAction = CompactChip.Builder(
            context,
            if (hosts.isEmpty()) "Pair Host" else "Open Mouse",
            createLaunchClickable(context, connectAddress = null),
            deviceParams
        )
            .setChipColors(ChipColors(argb(COLOR_EMERALD_ACCENT), argb(0xFF000000.toInt())))
            .build()

        return PrimaryLayout.Builder(deviceParams)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(primaryLabel)
            .setContent(columnBuilder.build())
            .setPrimaryChipContent(bottomAction)
            .build()
    }

    private fun createLaunchClickable(
        context: Context,
        connectAddress: String?
    ): ModifiersBuilders.Clickable {
        val activityBuilder = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(context.packageName)
            .setClassName(MainActivity::class.java.name)

        if (!connectAddress.isNullOrBlank()) {
            activityBuilder.addKeyToExtraMapping(
                MainActivity.EXTRA_CONNECT_ADDRESS,
                ActionBuilders.AndroidStringExtra.Builder()
                    .setValue(connectAddress)
                    .build()
            )
        }

        val launchAction = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(activityBuilder.build())
            .build()

        return ModifiersBuilders.Clickable.Builder()
            .setId(connectAddress ?: "open_main")
            .setOnClick(launchAction)
            .build()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val COLOR_EMERALD_ACCENT = 0xFF00E676.toInt()
        private const val COLOR_EMERALD_BG = 0xFF0D3322.toInt()
        private const val COLOR_EMERALD_TEXT = 0xFF69F0AE.toInt()
        private const val COLOR_SURFACE_BG = 0xFF1A1F24.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFFF3F6F9.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xFF9AA7B2.toInt()

        fun requestUpdate(context: Context) {
            runCatching {
                getUpdater(context).requestUpdate(SavedHostsTileService::class.java)
            }
        }
    }
}
