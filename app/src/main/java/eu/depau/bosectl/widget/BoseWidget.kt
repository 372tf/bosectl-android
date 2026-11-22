package eu.depau.bosectl.widget

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
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
import androidx.glance.unit.ColorProvider
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

// フォルダ風カラー設定（ARGBの16進数: 先頭2桁が透明度 00=透明 〜 FF=不透明）
private val WIDGET_BG_COLOR = ColorProvider(Color(0x20000000))       // フォルダ風の半透明グレー
private val SELECTED_CHIP_COLOR = ColorProvider(Color(0xE6FFFFFF))   // 選択中ボタン: 白
private val UNSELECTED_CHIP_COLOR = ColorProvider(Color(0x3DFFFFFF)) // 未選択ボタン: 半透明白グレー
private val TEXT_PRIMARY_COLOR = ColorProvider(Color(0xFFFFFFFF))    // 通常文字・アイコン: 白
private val TEXT_SUB_COLOR = ColorProvider(Color(0xE6F1F5F9))        // バッテリー等の補助文字: 明るい白
private val TEXT_SELECTED_COLOR = ColorProvider(Color(0xFF1F242D))   // 選択中ボタン内の文字・アイコン: 濃グレー

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
        val displayName = if (connected) deviceName else "Bose Control"
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
                .background(WIDGET_BG_COLOR)
                .cornerRadius(22.dp)
                .padding(horizontal = HORIZONTAL_PADDING, vertical = VERTICAL_PADDING),
        ) {
            Header(displayName, battery, connected, link, playingHere)

            if (!connected || starred.isEmpty()) {
                Text(
                    " ",
                    style = TextStyle(
                        color = TEXT_SUB_COLOR, fontSize = 12.sp
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
                    cols = modeSlots(size.width, MODE_CHIP_MIN_WIDTH
