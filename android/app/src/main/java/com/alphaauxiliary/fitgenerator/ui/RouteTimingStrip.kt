package com.alphaauxiliary.fitgenerator.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.alphaauxiliary.fitgenerator.core.ActivitySampleDto
import com.alphaauxiliary.fitgenerator.ui.theme.FieldPaper
import com.alphaauxiliary.fitgenerator.ui.theme.HeartBerry
import com.alphaauxiliary.fitgenerator.ui.theme.LaneGrayGreen
import com.alphaauxiliary.fitgenerator.ui.theme.PaceTeal
import com.alphaauxiliary.fitgenerator.ui.theme.ScoreboardInk
import com.alphaauxiliary.fitgenerator.ui.theme.TimingMetricTextStyle
import com.alphaauxiliary.fitgenerator.ui.theme.TrackRed
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.delay

internal data class TimingStripMetrics(
    val distance: String,
    val time: String,
    val pace: String,
    val heartRate: String,
    val lap: String,
    val timeLabel: String,
    val heartRateLabel: String,
    val lapLabel: String,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RouteTimingStrip(
    state: MainUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onPreviewSampleIndexChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    PreviewProgressEffect(
        state = state,
        onPreviewSampleIndexChanged = onPreviewSampleIndexChanged,
    )
    val metrics = state.toTimingStripMetrics()
    val status = timingStatus(state)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ROUTE_TIMING_STRIP_TAG),
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
        color = FieldPaper,
        contentColor = ScoreboardInk,
        border = BorderStroke(1.dp, LaneGrayGreen),
    ) {
        Column(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(
                    modifier = Modifier
                        .size(9.dp)
                        .background(TrackRed, CircleShape),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = status,
                    modifier = Modifier.weight(1f),
                    color = ScoreboardInk,
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics {
                            contentDescription = if (expanded) {
                                "收起模拟参数与导出"
                            } else {
                                "展开模拟参数与导出"
                            }
                            stateDescription = if (expanded) "已展开" else "已收起"
                        },
                ) {
                    Text(if (expanded) "收起" else "参数与导出")
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TimingMetric(label = "距离", value = metrics.distance)
                TimingMetric(label = metrics.timeLabel, value = metrics.time)
                TimingMetric(label = "配速", value = metrics.pace, valueColor = PaceTeal)
                TimingMetric(
                    label = metrics.heartRateLabel,
                    value = metrics.heartRate,
                    valueColor = HeartBerry,
                )
                TimingMetric(label = metrics.lapLabel, value = metrics.lap)
            }
        }
    }
}

@Composable
private fun TimingMetric(
    label: String,
    value: String,
    valueColor: Color = ScoreboardInk,
) {
    Column(
        modifier = Modifier
            .widthIn(min = 62.dp)
            .clearAndSetSemantics { contentDescription = "$label，$value" },
    ) {
        Text(
            text = label,
            color = ScoreboardInk,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = value,
            color = valueColor,
            style = TimingMetricTextStyle,
        )
    }
}

@Composable
private fun PreviewProgressEffect(
    state: MainUiState,
    onPreviewSampleIndexChanged: (Int) -> Unit,
) {
    val preview = state.preview ?: return
    val selectedIndex = resolveSampleIndex(preview.samples.size, state.previewSampleIndex) ?: return
    var playbackCompleted by remember(preview) { mutableStateOf(false) }
    if (
        state.phase != MainPhase.READY ||
        selectedIndex >= preview.samples.lastIndex ||
        playbackCompleted
    ) {
        return
    }

    val animatorScale = rememberSystemAnimatorScale()
    val latestIndexChanged by rememberUpdatedState(onPreviewSampleIndexChanged)

    LaunchedEffect(preview, selectedIndex, animatorScale) {
        if (animatorScale <= 0f) {
            latestIndexChanged(preview.samples.lastIndex)
            playbackCompleted = true
            return@LaunchedEffect
        }
        val nextIndex = selectedIndex + 1
        val sampleDelay = (
            preview.samples[nextIndex].timeMs.toDouble() -
                preview.samples[selectedIndex].timeMs.toDouble()
            ).coerceIn(
                MINIMUM_SAMPLE_DELAY_MILLIS.toDouble(),
                MAXIMUM_SAMPLE_DELAY_MILLIS.toDouble(),
            )
        val scaledDelay = (sampleDelay * animatorScale.toDouble())
            .roundToLong()
            .coerceAtLeast(1L)
        delay(scaledDelay)
        latestIndexChanged(nextIndex)
        if (nextIndex == preview.samples.lastIndex) playbackCompleted = true
    }
}

@Composable
private fun rememberSystemAnimatorScale(): Float {
    val resolver = LocalContext.current.contentResolver
    var scale by remember(resolver) { mutableStateOf(readAnimatorScale(resolver)) }

    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = readAnimatorScale(resolver)
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val registered = try {
            resolver.registerContentObserver(uri, false, observer)
            true
        } catch (_: SecurityException) {
            false
        }
        onDispose {
            if (registered) resolver.unregisterContentObserver(observer)
        }
    }
    return scale
}

private fun readAnimatorScale(resolver: android.content.ContentResolver): Float = try {
    val value = Settings.Global.getFloat(
        resolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        DEFAULT_ANIMATOR_SCALE,
    )
    if (value.isFinite() && value >= 0f) {
        value.coerceAtMost(MAXIMUM_ANIMATOR_SCALE)
    } else {
        DEFAULT_ANIMATOR_SCALE
    }
} catch (_: SecurityException) {
    DEFAULT_ANIMATOR_SCALE
}

internal fun MainUiState.toTimingStripMetrics(): TimingStripMetrics {
    val model = preview
    val sampleIndex = resolveSampleIndex(model?.samples?.size ?: 0, previewSampleIndex)
    val sample = sampleIndex?.let { model?.samples?.getOrNull(it) }
    val lapIndex = if (model != null && sampleIndex != null && model.laps.all { lap ->
            lap.startSample >= 0 &&
                lap.startSample <= lap.endSample &&
                lap.endSample < model.samples.size
        }
    ) {
        model.laps.indexOfFirst { lap -> sampleIndex in lap.startSample..lap.endSample }
            .takeIf { it >= 0 }
    } else {
        null
    }

    return TimingStripMetrics(
        distance = formatDistanceMeters(
            sample?.distanceCm?.div(CENTIMETERS_PER_METER) ?: routeDistanceMeters,
        ),
        time = sample?.let { formatDuration(it.timeMs) }
            ?: formatEstimatedDuration(routeDistanceMeters, paceSecondsPerKilometer),
        pace = sample?.paceSecondsPerKilometerOrNull()?.let(::formatPace)
            ?: if (sample == null && paceSecondsPerKilometer in
                MINIMUM_PLANNED_PACE_SECONDS..MAXIMUM_PLANNED_PACE_SECONDS
            ) {
                formatPace(paceSecondsPerKilometer)
            } else {
                PLACEHOLDER
            },
        heartRate = sample?.heartRateBpm?.takeIf { it in 1..MAXIMUM_DISPLAY_HEART_RATE }
            ?.let { "$it bpm" }
            ?: if (sample == null && restingHeartRate in MINIMUM_RESTING_HEART_RATE..MAXIMUM_RESTING_HEART_RATE) {
                "$restingHeartRate bpm"
            } else {
                PLACEHOLDER
            },
        lap = if (sample == null) {
            lapCount.takeIf { it in 1..MAXIMUM_PLANNED_LAPS }?.let { "$it 圈" } ?: PLACEHOLDER
        } else if (model != null && lapIndex != null) {
            "${lapIndex + 1}/${model.laps.size}"
        } else {
            PLACEHOLDER
        },
        timeLabel = if (sample == null) "预计" else "用时",
        heartRateLabel = if (sample == null) "静息心率" else "心率",
        lapLabel = if (sample == null) "计划" else "圈数",
    )
}

private fun ActivitySampleDto.paceSecondsPerKilometerOrNull(): Double? =
    speedMmPerSec.takeIf { it > 0 }?.let { MILLIMETERS_PER_KILOMETER / it.toDouble() }

private fun resolveSampleIndex(sampleCount: Int, requestedIndex: Int?): Int? =
    if (sampleCount <= 0) null else (requestedIndex ?: 0).coerceIn(0, sampleCount - 1)

private fun timingStatus(state: MainUiState): String = when (state.phase) {
    MainPhase.EMPTY -> "在地图上开始绘制路线"
    MainPhase.DRAWING -> "路线绘制中 · 点间至少 8 米"
    MainPhase.READY -> if (state.preview?.samples.isNullOrEmpty()) {
        "路线就绪 · 下一步预览"
    } else {
        "预览已同步到地图标记"
    }
    MainPhase.PREVIEWING -> "正在计算活动预览"
    MainPhase.EXPORTING -> "正在生成 FIT"
    MainPhase.ERROR -> "需要处理 · 路线仍已保留"
}

private fun formatDistanceMeters(distanceMeters: Double): String =
    if (distanceMeters.isFinite() && distanceMeters >= 0.0) {
        String.format(Locale.ROOT, "%.2f km", distanceMeters / METERS_PER_KILOMETER)
    } else {
        PLACEHOLDER
    }

private fun formatEstimatedDuration(distanceMeters: Double, paceSeconds: Double): String {
    if (!distanceMeters.isFinite() || distanceMeters < 0.0 || !paceSeconds.isFinite() ||
        paceSeconds !in MINIMUM_PLANNED_PACE_SECONDS..MAXIMUM_PLANNED_PACE_SECONDS
    ) {
        return PLACEHOLDER
    }
    val durationMillis = distanceMeters / METERS_PER_KILOMETER * paceSeconds * 1_000.0
    return if (durationMillis.isFinite() && durationMillis in 0.0..Long.MAX_VALUE.toDouble()) {
        formatDuration(durationMillis.roundToLong())
    } else {
        PLACEHOLDER
    }
}

private fun formatDuration(durationMillis: Long): String {
    if (durationMillis < 0L) return PLACEHOLDER
    val totalSeconds = durationMillis / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

private fun formatPace(secondsPerKilometer: Double): String {
    if (!secondsPerKilometer.isFinite() || secondsPerKilometer <= 0.0) return PLACEHOLDER
    val rounded = secondsPerKilometer.roundToLong().coerceAtLeast(1L)
    return String.format(Locale.ROOT, "%d'%02d\"/km", rounded / 60L, rounded % 60L)
}

private const val CENTIMETERS_PER_METER = 100.0
private const val METERS_PER_KILOMETER = 1_000.0
private const val MILLIMETERS_PER_KILOMETER = 1_000_000.0
internal const val ROUTE_TIMING_STRIP_TAG = "route_timing_strip"
private const val PLACEHOLDER = "--"
private const val MAXIMUM_DISPLAY_HEART_RATE = 255
private const val MINIMUM_RESTING_HEART_RATE = 30
private const val MAXIMUM_RESTING_HEART_RATE = 120
private const val MAXIMUM_PLANNED_LAPS = 100
private const val MINIMUM_PLANNED_PACE_SECONDS = 60.0
private const val MAXIMUM_PLANNED_PACE_SECONDS = 3_600.0
private const val MINIMUM_SAMPLE_DELAY_MILLIS = 16L
private const val MAXIMUM_SAMPLE_DELAY_MILLIS = 2_000L
private const val DEFAULT_ANIMATOR_SCALE = 1f
private const val MAXIMUM_ANIMATOR_SCALE = 10f
