package com.alphaauxiliary.fitgenerator.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alphaauxiliary.fitgenerator.ui.theme.FieldPaper
import com.alphaauxiliary.fitgenerator.ui.theme.HeartBerry
import com.alphaauxiliary.fitgenerator.ui.theme.LaneGrayGreen
import com.alphaauxiliary.fitgenerator.ui.theme.PaceTeal
import com.alphaauxiliary.fitgenerator.ui.theme.ScoreboardInk
import com.alphaauxiliary.fitgenerator.ui.theme.TimingMetricTextStyle
import com.alphaauxiliary.fitgenerator.ui.theme.TrackRed
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
internal fun SimulationSheet(
    state: MainUiState,
    onPaceChanged: (Double) -> Unit,
    onHeartRatesChanged: (Int, Int) -> Unit,
    onLapCountChanged: (Int) -> Unit,
    onExportCountChanged: (Int) -> Unit,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onDismissError: () -> Unit,
    onPreviewSampleIndexChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val sheetModifier = if (expanded) {
        modifier
            .fillMaxWidth()
            .fillMaxHeight(EXPANDED_SHEET_FRACTION)
    } else {
        modifier.fillMaxWidth()
    }

    Column(
        modifier = sheetModifier.semantics { isTraversalGroup = true },
    ) {
        RouteTimingStrip(
            state = state,
            expanded = expanded,
            onToggleExpanded = { expanded = !expanded },
            onPreviewSampleIndexChanged = onPreviewSampleIndexChanged,
        )

        state.errorMessage?.let { message ->
            ErrorNotice(message = message, onDismiss = onDismissError)
        }

        if (expanded) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                color = FieldPaper,
                contentColor = ScoreboardInk,
                border = BorderStroke(1.dp, LaneGrayGreen),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { isTraversalGroup = true }
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    SimulationParameters(
                        state = state,
                        onPaceChanged = onPaceChanged,
                        onHeartRatesChanged = onHeartRatesChanged,
                        onLapCountChanged = onLapCountChanged,
                        modifier = Modifier.semantics { traversalIndex = 1f },
                    )
                    HorizontalDivider(color = LaneGrayGreen)
                    PreviewControls(
                        state = state,
                        onPreview = onPreview,
                        onPreviewSampleIndexChanged = onPreviewSampleIndexChanged,
                        modifier = Modifier.semantics { traversalIndex = 2f },
                    )
                    HorizontalDivider(color = LaneGrayGreen)
                    ExportControls(
                        state = state,
                        onExportCountChanged = onExportCountChanged,
                        onExport = onExport,
                        modifier = Modifier.semantics { traversalIndex = 3f },
                    )
                }
            }
        }
    }
}

@Composable
private fun SimulationParameters(
    state: MainUiState,
    onPaceChanged: (Double) -> Unit,
    onHeartRatesChanged: (Int, Int) -> Unit,
    onLapCountChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val controlsEnabled = state.isInitialized &&
        state.phase !in setOf(MainPhase.PREVIEWING, MainPhase.EXPORTING)
    val paceIsValid = state.paceSecondsPerKilometer.isFinite() &&
        state.paceSecondsPerKilometer in MINIMUM_PACE_SECONDS..MAXIMUM_PACE_SECONDS
    val heartRatesAreValid = state.restingHeartRate in
        MINIMUM_RESTING_HEART_RATE..MAXIMUM_RESTING_HEART_RATE &&
        state.maximumHeartRate in MINIMUM_MAXIMUM_HEART_RATE..MAXIMUM_HEART_RATE &&
        state.restingHeartRate < state.maximumHeartRate

    WorkflowSection(
        title = "1 模拟",
        supportingText = "先确认配速、心率范围和圈数。修改后需要重新预览。",
        modifier = modifier,
    ) {
        ParameterStepper(
            label = "目标配速",
            value = formatPaceSetting(state.paceSecondsPerKilometer),
            decreaseDescription = "降低目标配速五秒",
            increaseDescription = "提高目标配速五秒",
            canDecrease = controlsEnabled && paceIsValid &&
                state.paceSecondsPerKilometer > MINIMUM_PACE_SECONDS,
            canIncrease = controlsEnabled && paceIsValid &&
                state.paceSecondsPerKilometer < MAXIMUM_PACE_SECONDS,
            onDecrease = {
                onPaceChanged(
                    (state.paceSecondsPerKilometer - PACE_STEP_SECONDS)
                        .coerceAtLeast(MINIMUM_PACE_SECONDS),
                )
            },
            onIncrease = {
                onPaceChanged(
                    (state.paceSecondsPerKilometer + PACE_STEP_SECONDS)
                        .coerceAtMost(MAXIMUM_PACE_SECONDS),
                )
            },
            valueColor = PaceTeal,
        )
        ParameterStepper(
            label = "静息心率",
            value = "${state.restingHeartRate} bpm",
            decreaseDescription = "降低静息心率",
            increaseDescription = "提高静息心率",
            canDecrease = controlsEnabled && heartRatesAreValid &&
                state.restingHeartRate > MINIMUM_RESTING_HEART_RATE,
            canIncrease = controlsEnabled && heartRatesAreValid &&
                state.restingHeartRate < MAXIMUM_RESTING_HEART_RATE &&
                state.restingHeartRate + 1 < state.maximumHeartRate,
            onDecrease = {
                onHeartRatesChanged(state.restingHeartRate - 1, state.maximumHeartRate)
            },
            onIncrease = {
                onHeartRatesChanged(state.restingHeartRate + 1, state.maximumHeartRate)
            },
            valueColor = HeartBerry,
        )
        ParameterStepper(
            label = "最高心率",
            value = "${state.maximumHeartRate} bpm",
            decreaseDescription = "降低最高心率",
            increaseDescription = "提高最高心率",
            canDecrease = controlsEnabled && heartRatesAreValid &&
                state.maximumHeartRate > MINIMUM_MAXIMUM_HEART_RATE &&
                state.maximumHeartRate - 1 > state.restingHeartRate,
            canIncrease = controlsEnabled && heartRatesAreValid &&
                state.maximumHeartRate < MAXIMUM_HEART_RATE,
            onDecrease = {
                onHeartRatesChanged(state.restingHeartRate, state.maximumHeartRate - 1)
            },
            onIncrease = {
                onHeartRatesChanged(state.restingHeartRate, state.maximumHeartRate + 1)
            },
            valueColor = HeartBerry,
        )
        ParameterStepper(
            label = "模拟圈数",
            value = "${state.lapCount} 圈",
            decreaseDescription = "减少模拟圈数",
            increaseDescription = "增加模拟圈数",
            canDecrease = controlsEnabled && state.lapCount in
                (MINIMUM_LAP_COUNT + 1)..MAXIMUM_LAP_COUNT,
            canIncrease = controlsEnabled && state.lapCount in
                MINIMUM_LAP_COUNT until MAXIMUM_LAP_COUNT,
            onDecrease = { onLapCountChanged(state.lapCount - 1) },
            onIncrease = { onLapCountChanged(state.lapCount + 1) },
        )
    }
}

@Composable
private fun PreviewControls(
    state: MainUiState,
    onPreview: () -> Unit,
    onPreviewSampleIndexChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    WorkflowSection(
        title = "2 预览",
        supportingText = "预览完成后，计时带与地图标记使用同一个采样点。",
        modifier = modifier,
    ) {
        OutlinedButton(
            onClick = onPreview,
            enabled = state.canPreview,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "生成活动预览" },
            border = BorderStroke(1.dp, ScoreboardInk),
        ) {
            Text(if (state.phase == MainPhase.PREVIEWING) "预览计算中" else "生成活动预览")
        }

        val samples = state.preview?.samples.orEmpty()
        if (samples.isNotEmpty()) {
            val selectedIndex = state.previewSampleIndex
                ?.coerceIn(0, samples.lastIndex)
                ?: 0
            Text(
                text = "预览进度 ${selectedIndex + 1}/${samples.size}",
                color = ScoreboardInk,
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = selectedIndex.toFloat(),
                onValueChange = { value ->
                    onPreviewSampleIndexChanged(value.roundToInt().coerceIn(0, samples.lastIndex))
                },
                valueRange = 0f..samples.lastIndex.toFloat().coerceAtLeast(1f),
                enabled = samples.size > 1 &&
                    state.phase !in setOf(MainPhase.PREVIEWING, MainPhase.EXPORTING),
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .semantics {
                        contentDescription = "预览进度"
                        stateDescription = "第 ${selectedIndex + 1} 个采样点，共 ${samples.size} 个"
                    },
            )
        }
    }
}

@Composable
private fun ExportControls(
    state: MainUiState,
    onExportCountChanged: (Int) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controlsEnabled = state.isInitialized &&
        state.phase !in setOf(MainPhase.PREVIEWING, MainPhase.EXPORTING)
    val exportReady = state.canExport &&
        !state.preview?.samples.isNullOrEmpty() &&
        !state.preview?.laps.isNullOrEmpty()

    WorkflowSection(
        title = "3 导出",
        supportingText = "生成使用刚才预览的路线、参数与随机种子。",
        modifier = modifier,
    ) {
        ParameterStepper(
            label = "生成份数",
            value = "${state.exportCount} 份",
            decreaseDescription = "减少生成份数",
            increaseDescription = "增加生成份数",
            canDecrease = controlsEnabled && state.exportCount in
                (MINIMUM_EXPORT_COUNT + 1)..MAXIMUM_EXPORT_COUNT,
            canIncrease = controlsEnabled && state.exportCount in
                MINIMUM_EXPORT_COUNT until MAXIMUM_EXPORT_COUNT,
            onDecrease = { onExportCountChanged(state.exportCount - 1) },
            onIncrease = { onExportCountChanged(state.exportCount + 1) },
        )
        Button(
            onClick = onExport,
            enabled = exportReady,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics { contentDescription = "生成 FIT" },
            colors = ButtonDefaults.buttonColors(
                containerColor = TrackRed,
                contentColor = FieldPaper,
                disabledContainerColor = LaneGrayGreen,
                disabledContentColor = ScoreboardInk,
            ),
        ) {
            Text(
                text = if (state.phase == MainPhase.EXPORTING) "正在生成 FIT" else "生成 FIT",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun WorkflowSection(
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            color = ScoreboardInk,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = supportingText,
            color = ScoreboardInk,
            style = MaterialTheme.typography.bodySmall,
        )
        content()
    }
}

@Composable
private fun ParameterStepper(
    label: String,
    value: String,
    decreaseDescription: String,
    increaseDescription: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    valueColor: androidx.compose.ui.graphics.Color = ScoreboardInk,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics { contentDescription = "$label，$value" },
            ) {
                Text(text = label, style = MaterialTheme.typography.labelMedium)
                Text(text = value, color = valueColor, style = TimingMetricTextStyle)
            }
            OutlinedButton(
                onClick = onDecrease,
                enabled = canDecrease,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = decreaseDescription },
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text("−")
            }
            OutlinedButton(
                onClick = onIncrease,
                enabled = canIncrease,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = increaseDescription },
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text("+")
            }
        }
    }
}

@Composable
private fun ErrorNotice(message: String, onDismiss: () -> Unit) {
    val actionableMessage = message.ifBlank { "操作未完成，请检查路线和参数后重试。" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = actionableMessage,
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "需要处理：$actionableMessage"
                },
            color = ScoreboardInk,
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "关闭错误提示" },
        ) {
            Text("知道了")
        }
    }
}

private fun formatPaceSetting(secondsPerKilometer: Double): String {
    if (!secondsPerKilometer.isFinite() ||
        secondsPerKilometer !in MINIMUM_PACE_SECONDS..MAXIMUM_PACE_SECONDS
    ) {
        return "--"
    }
    val seconds = secondsPerKilometer.roundToLong()
    return String.format(Locale.ROOT, "%d'%02d\"/km", seconds / 60L, seconds % 60L)
}

private const val EXPANDED_SHEET_FRACTION = 0.72f
private const val PACE_STEP_SECONDS = 5.0
private const val MINIMUM_PACE_SECONDS = 60.0
private const val MAXIMUM_PACE_SECONDS = 3_600.0
private const val MINIMUM_RESTING_HEART_RATE = 30
private const val MAXIMUM_RESTING_HEART_RATE = 120
private const val MINIMUM_MAXIMUM_HEART_RATE = 100
private const val MAXIMUM_HEART_RATE = 220
private const val MINIMUM_LAP_COUNT = 1
private const val MAXIMUM_LAP_COUNT = 100
private const val MINIMUM_EXPORT_COUNT = 1
private const val MAXIMUM_EXPORT_COUNT = 20
