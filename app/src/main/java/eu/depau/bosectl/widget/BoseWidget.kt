package eu.depau.bosectl.widget

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.Switch
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import eu.depau.bosectl.R
import eu.depau.bosectl.bmap.Prompt
import eu.depau.bosectl.data.CachedMode
import eu.depau.bosectl.data.Prefs
import eu.depau.bosectl.data.dataStore
import eu.depau.bosectl.data.LinkLayer
import eu.depau.bosectl.data.decodeCachedModes
import eu.depau.bosectl.data.encodeCachedModes
import eu.depau.bosectl.ui.MainActivity
import eu.depau.bosectl.ui.promptDrawable
import kotlinx.coroutines.flow.first

private data class Battery(
    val left: Int?, val right: Int?, val case: Int?, val overall: Int?,
)

class BoseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BoseWidget()
}

private val PREVIEW_SIZE = DpSize(250.dp, 200.dp)

private val PREVIEW_PREFS = mutablePreferencesOf(
    Prefs.CACHE_DEVICE_NAME to "QC Ultra Earbuds",
    Prefs.CACHE_CONNECTED to true,
    Prefs.CACHE_LINK to LinkLayer.CLASSIC.id,
    Prefs.CACHE_PLAYING_HERE to true,
    Prefs.CACHE_BAT_LEFT to 80,
    Prefs.CACHE_BAT_RIGHT to 75,
    Prefs.CACHE_BAT_CASE to 70,
    Prefs.CACHE_CURRENT_MODE to 0,
    Prefs.CACHE_SPATIAL to 0,
    Prefs.CACHE_ANC to true,
    Prefs.CACHE_CNC to 0,
    Prefs.CACHE_TOUCH to true,
    Prefs.CACHE_STARRED to encodeCachedModes(
        listOf(
            CachedMode(0, Prompt.QUIET.id, "Quiet"),
            CachedMode(1, Prompt.AWARE.id, "Aware"),
            CachedMode(2, Prompt.IMMERSION.id, "Immersion"),
        )
    ),
)

suspend fun publishWidgetPreview(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    val result = GlanceAppWidgetManager(context).setWidgetPreviews(BoseWidgetReceiver::class)
    Log.d("BoseWidget", "setWidgetPreviews -> $result")
}

private val VERTICAL_PADDING = 10.dp
private val HORIZONTAL_PADDING = 10.dp
private val HEADER_HEIGHT = 24.dp
private val MODE_BUTTON_HEIGHT = 57.dp
private val SPATIAL_HEIGHT = 36.dp
private val TOGGLE_HEIGHT = 38.dp
private val STEPPER_HEIGHT = 28.dp
private val CHIP_HEIGHT = 32.dp
private val GAP = 6.dp

private val CONTROLS_MIN_HEIGHT =
    VERTICAL_PADDING * 2 + HEADER_HEIGHT + MODE_BUTTON_HEIGHT + GAP + SPATIAL_HEIGHT +
        GAP + TOGGLE_HEIGHT + GAP + STEPPER_HEIGHT

private val BUTTONS_MIN_HEIGHT =
    VERTICAL_PADDING * 2 + HEADER_HEIGHT + MODE_BUTTON_HEIGHT + GAP + SPATIAL_HEIGHT

private val CHIP_ICON_MIN_WIDTH = 300.dp
private val MODE_BUTTON_MIN_WIDTH = 56.dp
private val MODE_CHIP_MIN_WIDTH = 56.dp
private val WIDE_MIN_WIDTH = 420.dp
private val TWO_BY_TWO_MAX_WIDTH = 240.dp
private const val NARROW_MAX_MODES = 4

private fun modeSlots(width: Dp, cellWidth: Dp): Int =
    ((width - HORIZONTAL_PADDING * 2) / cellWidth).toInt().coerceAtLeast(1)

private fun visible(modes: List<CachedMode>, cols: Int): List<CachedMode> =
    if (modes.size <= cols) modes else modes.take((cols - 1).coerceAtLeast(1))

class BoseWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = runCatching { context.dataStore.data.first() }.getOrNull()
        provideContent {
            val prefs by context.dataStore.data.collectAsState(initial)
            GlanceTheme { WidgetContent(prefs) }
        }
    }

    override val previewSizeMode = SizeMode.Responsive(setOf(PREVIEW_SIZE))

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val cached = runCatching { context.dataStore.data.first() }.getOrNull()
        val prefs = cached?.takeIf { decodeCachedModes(it[Prefs.CACHE_STARRED]).isNotEmpty() }
            ?: PREVIEW_PREFS
        provideContent { GlanceTheme { WidgetContent(prefs) } }
    }

    @Composable
    private fun WidgetContent(prefs: Preferences?) {
        val size = LocalSize.current
        val is2x2 = size.width < TWO_BY_TWO_MAX_WIDTH && size.height >= 115.dp
        val compact = size.height < BUTTONS_MIN_HEIGHT
        val showControls = size.height >= CONTROLS_MIN_HEIGHT
        val wideChips = size.width >= CHIP_ICON_MIN_WIDTH
        val is4x2 = !is2x2 && !compact && !showControls && size.width >= 300.dp

        val connected = prefs?.get(Prefs.CACHE_CONNECTED) ?: false
        val link = prefs?.get(Prefs.CACHE_LINK)
        val playingHere = prefs?.get(Prefs.CACHE_PLAYING_HERE) ?: false
        val deviceName = prefs?.get(Prefs.CACHE_DEVICE_NAME) ?: "Bose Control"
        val battery = Battery(
            left = prefs?.get(Prefs.CACHE_BAT_LEFT),
            right = prefs?.get(Prefs.CACHE_BAT_RIGHT),
            case = prefs?.get(Prefs.CACHE_BAT_CASE),
            overall = prefs?.get(Prefs.CACHE_BAT_OVERALL),
        )
        val currentMode = prefs?.get(Prefs.CACHE_CURRENT_MODE)
        val spatial = prefs?.get(Prefs.CACHE_SPATIAL) ?: 0
        val anc = prefs?.get(Prefs.CACHE_ANC) ?: true
        val cnc = prefs?.get(Prefs.CACHE_CNC) ?: 0
        val touch = prefs?.get(Prefs.CACHE_TOUCH) ?: true
        val starred = decodeCachedModes(prefs?.get(Prefs.CACHE_STARRED))

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(20.dp)
                .padding(horizontal = HORIZONTAL_PADDING, vertical = VERTICAL_PADDING),
        ) {
            Header(deviceName, battery, connected, link, playingHere)

            if (starred.isEmpty()) {
                Text(
                    "Open the app to load your modes",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp
                    ),
                )
                return@Column
            }

            // 2x2専用レイアウト
            if (is2x2) {
                val modes4 = starred.take(4)
                val row1 = modes4.take(2)
                val row2 = modes4.drop(2).take(2)

                Spacer(GlanceModifier.defaultWeight())
                ModeGridRow(row1, currentMode, connected, GlanceModifier.height(29.dp))
                if (row2.isNotEmpty()) {
                    Spacer(GlanceModifier.height(5.dp))
                    ModeGridRow(row2, currentMode, connected, GlanceModifier.height(29.dp))
                }
                Spacer(GlanceModifier.height(6.dp))
                CompactSpatialRow(spatial, connected, GlanceModifier.height(29.dp))
                Spacer(GlanceModifier.height(2.dp))
                return@Column
            }

            if (compact) {
                Spacer(GlanceModifier.defaultWeight())
                ModeChipRow(
                    starred, currentMode, connected,
                    cols = modeSlots(size.width, MODE_CHIP_MIN_WIDTH),
                    showIcon = wideChips,
                    modifier = GlanceModifier.height(CHIP_HEIGHT),
                )
                return@Column
            }

            // 4x2 / 3x2 / 4x3 レイアウト
            Spacer(GlanceModifier.defaultWeight())
            ModeButtonRow(
                starred, currentMode, connected,
                cols = modeSlots(size.width, MODE_BUTTON_MIN_WIDTH).let {
                    if (size.width >= WIDE_MIN_WIDTH) it else minOf(it, NARROW_MAX_MODES)
                },
                modifier = GlanceModifier.height(MODE_BUTTON_HEIGHT),
            )
            Spacer(GlanceModifier.height(if (is4x2) 9.dp else GAP))
            SpatialRow(
                spatial, connected,
                modifier = GlanceModifier.height(SPATIAL_HEIGHT),
            )

            if (!showControls) {
                Spacer(GlanceModifier.height(2.dp))
            }

            if (showControls) {
                Spacer(GlanceModifier.defaultWeight())
                Row(modifier = GlanceModifier.fillMaxWidth().height(TOGGLE_HEIGHT)) {
                    ToggleChip(
                        checked = anc, label = "ANC", showIcon = wideChips,
                        icon = if (anc) R.drawable.ic_noise_control_on
                        else R.drawable.ic_noise_control_off,
                        action = actionRunCallback<ToggleAncAction>(),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    ToggleChip(
                        checked = touch, label = "Touch", showIcon = wideChips,
                        icon = R.drawable.ic_touch_app,
                        action = actionRunCallback<ToggleTouchControlsAction>(),
                    )
                }
                Spacer(GlanceModifier.height(GAP))
                CncRow(cnc, enabled = anc && connected)
            }
        }
    }

    /** 2x2グリッド用のモード2列行 */
    @Composable
    private fun ModeGridRow(
        modes: List<CachedMode>,
        currentMode: Int?,
        connected: Boolean,
        modifier: GlanceModifier,
    ) {
        Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            modes.forEachIndexed { i, mode ->
                if (i > 0) Spacer(GlanceModifier.width(4.dp))
                val selected = connected && mode.idx == currentMode
                Row(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .background(chipColor(selected))
                        .cornerRadius(9.dp)
                        .fillMaxHeight()
                        .clickable(
                            actionRunCallback<SetModeAction>(SetModeAction.params(mode.idx))
                        )
                        .padding(horizontal = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TintedIcon(promptDrawable(mode.promptId), selected, 12.dp)
                    Spacer(GlanceModifier.width(3.dp))
                    Text(mode.name, style = chipText(selected, 10.5.sp), maxLines = 1)
                }
            }
        }
    }

    /** 2x2幅に「Motion」まで省略されずに収まる空間オーディオ3列行 */
    @Composable
    private fun CompactSpatialRow(
        spatial: Int,
        connected: Boolean,
        modifier: GlanceModifier,
    ) {
        val options = listOf(
            Triple(0, "Off", R.drawable.ic_spatial_audio_off),
            Triple(1, "Still", R.drawable.ic_spatial_tracking),
            Triple(2, "Motion", R.drawable.ic_spatial_audio),
        )
        Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { i, option ->
                if (i > 0) Spacer(GlanceModifier.width(2.dp))
                val selected = connected && spatial == option.first
                Row(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .background(chipColor(selected))
                        .cornerRadius(9.dp)
                        .clickable(
                            actionRunCallback<SetSpatialAction>(
                                SetSpatialAction.params(option.first)
                            )
                        )
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TintedIcon(option.third, selected, 9.dp)
                    Spacer(GlanceModifier.width(1.5.dp))
                    Text(option.second, style = chipText(selected, 9.sp), maxLines = 1)
                }
            }
        }
    }

    @Composable
    private fun RowScope.ToggleChip(
        checked: Boolean,
        label: String,
        showIcon: Boolean,
        icon: Int,
        action: Action,
    ) {
        Row(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(14.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showIcon) {
                Image(
                    provider = ImageProvider(icon),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                    modifier = GlanceModifier.size(18.dp),
                )
                Spacer(GlanceModifier.width(6.dp))
            }
            Switch(
                checked = checked,
                onCheckedChange = action,
                text = label,
                modifier = GlanceModifier.defaultWeight(),
            )
        }
    }

    @Composable
    private fun LinkStatusIcon(connected: Boolean, link: String?, playingHere: Boolean) {
        val (icon, description) = when {
            !connected -> R.drawable.ic_bluetooth_disabled to "Disconnected"
            playingHere -> R.drawable.ic_media_bluetooth_on to "Playing on this phone"
            link == LinkLayer.LE.id -> R.drawable.ic_bluetooth_searching to
                "Connected over Bluetooth LE"
            else -> R.drawable.ic_bluetooth_connected to "Connected over Bluetooth"
        }
        Image(
            provider = ImageProvider(icon),
            contentDescription = description,
            colorFilter = ColorFilter.tint(
                if (connected) GlanceTheme.colors.primary
                else GlanceTheme.colors.onSurfaceVariant
            ),
            modifier = GlanceModifier.size(15.dp),
        )
        if (connected) Spacer(GlanceModifier.width(4.dp))
    }

    @Composable
    private fun Header(
        name: String,
        battery: Battery,
        connected: Boolean,
        link: String?,
        playingHere: Boolean,
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .cornerRadius(10.dp)
                .clickable(actionStartActivity<MainActivity>())
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = GlanceModifier.size(18.dp).cornerRadius(9.dp),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                name,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            LinkStatusIcon(connected, link, playingHere)
            if (connected) {
                BatteryCell(R.drawable.ic_earbud_left, battery.left)
                BatteryCell(R.drawable.ic_earbud_right, battery.right)
                BatteryCell(R.drawable.ic_earbud_case, battery.case)
                if (battery.left == null && battery.right == null && battery.case == null) {
                    BatteryCell(R.drawable.ic_battery_std, battery.overall)
                }
            }
        }
    }

    @Composable
    private fun BatteryCell(icon: Int, percent: Int?) {
        if (percent == null) return
        Row(
            modifier = GlanceModifier.padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier.size(13.dp),
            )
            Text(
                "$percent%",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp
                ),
                maxLines = 1,
            )
        }
    }

    @Composable
    private fun ModeChipRow(
        modes: List<CachedMode>,
        currentMode: Int?,
        connected: Boolean,
        cols: Int,
        showIcon: Boolean,
        modifier: GlanceModifier,
    ) {
        Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            visible(modes, cols).forEachIndexed { i, mode ->
                if (i > 0) Spacer(GlanceModifier.width(4.dp))
                val selected = connected && mode.idx == currentMode
                Row(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .background(chipColor(selected))
                        .cornerRadius(12.dp)
                        .fillMaxHeight()
                        .clickable(
                            actionRunCallback<SetModeAction>(SetModeAction.params(mode.idx))
                        )
                        .padding(horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showIcon) {
                        TintedIcon(promptDrawable(mode.promptId), selected, 14.dp)
                        Spacer(GlanceModifier.width(3.dp))
                    }
                    Text(mode.name, style = chipText(selected, 11.sp), maxLines = 1)
                }
            }
            Overflow(modes, cols)
        }
    }

    @Composable
    private fun ModeButtonRow(
        modes: List<CachedMode>,
        currentMode: Int?,
        connected: Boolean,
        cols: Int,
        modifier: GlanceModifier,
    ) {
        Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            visible(modes, cols).forEachIndexed { i, mode ->
                if (i > 0) Spacer(GlanceModifier.width(4.dp))
                val selected = connected && mode.idx == currentMode
                Column(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .background(chipColor(selected))
                        .cornerRadius(12.dp)
                        .fillMaxHeight()
                        .clickable(
                            actionRunCallback<SetModeAction>(SetModeAction.params(mode.idx))
                        )
                        .padding(horizontal = 2.dp, vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TintedIcon(promptDrawable(mode.promptId), selected, 19.dp)
                    Spacer(GlanceModifier.height(2.5.dp))
                    Text(mode.name, style = chipText(selected, 11.sp), maxLines = 1)
                }
            }
            Overflow(modes, cols)
        }
    }

    @Composable
    private fun SpatialRow(
        spatial: Int,
        connected: Boolean,
        modifier: GlanceModifier,
    ) {
        val options = listOf(
            Triple(0, "Off", R.drawable.ic_spatial_audio_off),
            Triple(1, "Still", R.drawable.ic_spatial_tracking),
            Triple(2, "Motion", R.drawable.ic_spatial_audio),
        )
        Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { i, option ->
                if (i > 0) Spacer(GlanceModifier.width(6.dp))
                val selected = connected && spatial == option.first
                Row(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .background(chipColor(selected))
                        .cornerRadius(12.dp)
                        .clickable(
                            actionRunCallback<SetSpatialAction>(
                                SetSpatialAction.params(option.first)
                            )
                        )
                        .fillMaxHeight()
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TintedIcon(option.third, selected, 15.dp)
                    Spacer(GlanceModifier.width(4.dp))
                    Text(option.second, style = chipText(selected, 11.sp), maxLines = 1)
                }
            }
        }
    }

    @Composable
    private fun CncRow(cncLevel: Int, enabled: Boolean) {
        val display = 10 - cncLevel
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton("−", AdjustCncAction.params(-1), enabled)
            Spacer(GlanceModifier.width(6.dp))
            Text(
                "Noise cancelling $display/10",
                style = TextStyle(
                    color = if (enabled) GlanceTheme.colors.onSurface
                    else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(6.dp))
            StepButton("+", AdjustCncAction.params(1), enabled)
        }
    }

    @Composable
    private fun StepButton(label: String, params: ActionParameters, enabled: Boolean) {
        Text(
            label,
            style = TextStyle(
                color = if (enabled) GlanceTheme.colors.onSecondaryContainer
                else GlanceTheme.colors.onSurfaceVariant,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            modifier = GlanceModifier
                .width(40.dp)
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(12.dp)
                .clickable(actionRunCallback<AdjustCncAction>(params))
                .padding(vertical = 4.dp),
        )
    }

    @Composable
    private fun Overflow(modes: List<CachedMode>, cols: Int) {
        val hidden = modes.size - visible(modes, cols).size
        if (hidden <= 0) return
        Spacer(GlanceModifier.width(6.dp))
        Text(
            "+$hidden",
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            ),
            modifier = GlanceModifier
                .width(36.dp)
                .cornerRadius(14.dp)
                .clickable(actionStartActivity<MainActivity>())
                .padding(vertical = 6.dp),
        )
    }

    @Composable
    private fun TintedIcon(resId: Int, selected: Boolean, iconSize: Dp) {
        Image(
            provider = ImageProvider(resId),
            contentDescription = null,
            colorFilter = ColorFilter.tint(
                if (selected) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurface
            ),
            modifier = GlanceModifier.size(iconSize),
        )
    }

    @Composable
    private fun chipColor(selected: Boolean) =
        if (selected) GlanceTheme.colors.primary else GlanceTheme.colors.secondaryContainer

    @Composable
    private fun chipText(selected: Boolean, fontSize: TextUnit = 11.sp) = TextStyle(
        color = if (selected) GlanceTheme.colors.onPrimary
        else GlanceTheme.colors.onSecondaryContainer,
        fontSize = fontSize,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        textAlign = TextAlign.Center,
    )
}
