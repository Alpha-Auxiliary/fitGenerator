package com.alphaauxiliary.fitgenerator.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alphaauxiliary.fitgenerator.map.GeoCoordinate
import com.alphaauxiliary.fitgenerator.map.LocationSearchResult
import com.alphaauxiliary.fitgenerator.ui.theme.FieldPaper
import com.alphaauxiliary.fitgenerator.ui.theme.LaneGrayGreen
import com.alphaauxiliary.fitgenerator.ui.theme.ScoreboardInk

@Composable
internal fun MainScreen(
    state: MainUiState,
    onSearchQueryChanged: (String) -> Unit,
    onSearchResultSelected: (LocationSearchResult) -> Unit,
    onOpenSettings: () -> Unit,
    onToggleDrawing: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onLocate: () -> Unit,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onPaceChanged: (Double) -> Unit,
    onHeartRatesChanged: (Int, Int) -> Unit,
    onLapCountChanged: (Int) -> Unit,
    onExportCountChanged: (Int) -> Unit,
    onPreviewSampleIndexChanged: (Int) -> Unit,
    onDismissError: () -> Unit,
    onDrawPoint: (GeoCoordinate) -> Unit,
    onSelectVertex: (Int?) -> Unit,
    onMoveSelectedVertex: (GeoCoordinate) -> Unit,
    onMapCameraChanged: (GeoCoordinate, Double) -> Unit,
    onMapError: (String) -> Unit,
    mapContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .background(FieldPaper)
            .systemBarsPadding()
            .imePadding(),
        color = FieldPaper,
        contentColor = ScoreboardInk,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            MainTopBar(onOpenSettings = onOpenSettings)

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
            ) {
                val horizontalMapActions = maxHeight < COMPACT_MAP_HEIGHT &&
                    maxWidth >= HORIZONTAL_MAP_MINIMUM_WIDTH

                if (mapContent == null) {
                    RouteMap(
                        state = state.routeMapState,
                        onDrawPoint = onDrawPoint,
                        onSelectVertex = onSelectVertex,
                        onMoveSelectedVertex = onMoveSelectedVertex,
                        onCameraChanged = onMapCameraChanged,
                        onMapError = onMapError,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    mapContent()
                }

                SearchWorkbench(
                    query = state.searchQuery,
                    results = state.searchResults,
                    isSearching = state.isSearching,
                    enabled = state.isInitialized,
                    message = state.searchMessage,
                    providerName = state.activeProviderName,
                    providerAttribution = state.providerAttribution,
                    onQueryChanged = onSearchQueryChanged,
                    onResultSelected = onSearchResultSelected,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .widthIn(max = if (horizontalMapActions) 300.dp else 520.dp)
                        .fillMaxWidth()
                        .padding(
                            start = 12.dp,
                            end = if (horizontalMapActions) 0.dp else 76.dp,
                            top = 12.dp,
                        ),
                )

                MapActionRail(
                    state = state,
                    onToggleDrawing = onToggleDrawing,
                    onUndo = onUndo,
                    onClear = { confirmClear = true },
                    onLocate = onLocate,
                    horizontal = horizontalMapActions,
                    modifier = Modifier
                        .align(
                            if (horizontalMapActions) Alignment.TopEnd else Alignment.CenterEnd,
                        )
                        .padding(
                            top = if (horizontalMapActions) 12.dp else 0.dp,
                            end = 12.dp,
                        ),
                )
            }

            SimulationSheet(
                state = state,
                onPaceChanged = onPaceChanged,
                onHeartRatesChanged = onHeartRatesChanged,
                onLapCountChanged = onLapCountChanged,
                onExportCountChanged = onExportCountChanged,
                onPreview = onPreview,
                onExport = onExport,
                onDismissError = onDismissError,
                onPreviewSampleIndexChanged = onPreviewSampleIndexChanged,
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空当前路线？") },
            text = { Text("所有绘制点和当前预览都会移除。地图设置不会改变。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                ) {
                    Text("清空路线", color = ScoreboardInk)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("保留路线") }
            },
            containerColor = FieldPaper,
            titleContentColor = ScoreboardInk,
            textContentColor = ScoreboardInk,
        )
    }
}

@Composable
private fun MainTopBar(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ScoreboardInk)
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "校园跑 FIT",
                modifier = Modifier.semantics { heading() },
                color = FieldPaper,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "路线检录台",
                color = LaneGrayGreen,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .semantics { contentDescription = "打开地图与应用设置" },
            colors = ButtonDefaults.textButtonColors(contentColor = FieldPaper),
        ) {
            Text("设置")
        }
    }
}

@Composable
private fun SearchWorkbench(
    query: String,
    results: List<LocationSearchResult>,
    isSearching: Boolean,
    enabled: Boolean,
    message: String?,
    providerName: String,
    providerAttribution: String,
    onQueryChanged: (String) -> Unit,
    onResultSelected: (LocationSearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "搜索校园、操场或地点"
                    stateDescription = when {
                        !enabled -> "暂不可用"
                        isSearching -> "正在搜索"
                        else -> "可输入"
                    }
                },
            label = { Text("搜索地点") },
            placeholder = { Text("例如：学校东操场") },
            supportingText = {
                Text(
                    text = "地图源：$providerName · $providerAttribution",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .sizeIn(minWidth = 20.dp, minHeight = 20.dp)
                            .clearAndSetSemantics { },
                        color = ScoreboardInk,
                        strokeWidth = 2.dp,
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() },
            ),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )

        if (results.isNotEmpty() || message != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .background(FieldPaper, MaterialTheme.shapes.medium)
                    .border(1.dp, LaneGrayGreen, MaterialTheme.shapes.medium)
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                results.forEachIndexed { index, result ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clickable(
                                role = Role.Button,
                                onClick = {
                                    focusManager.clearFocus()
                                    onResultSelected(result)
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .semantics {
                                contentDescription = "定位到 ${result.label}"
                            },
                    ) {
                        Text(
                            text = result.label,
                            color = ScoreboardInk,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = result.attribution,
                            color = ScoreboardInk,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (index != results.lastIndex) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .height(1.dp)
                                .background(LaneGrayGreen),
                        )
                    }
                }
                message?.let {
                    Text(
                        text = it,
                        modifier = Modifier
                            .padding(12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        color = ScoreboardInk,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MapActionRail(
    state: MainUiState,
    onToggleDrawing: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onLocate: () -> Unit,
    horizontal: Boolean,
    modifier: Modifier = Modifier,
) {
    val hasPoints = state.routeMapState.canonicalWgs84Points.isNotEmpty()
    val routeActionsEnabled = state.isInitialized &&
        state.phase !in setOf(MainPhase.PREVIEWING, MainPhase.EXPORTING)
    val actions: @Composable () -> Unit = {
        MapActionButton(
            label = "定位",
            description = "定位到路线起点",
            enabled = hasPoints && routeActionsEnabled,
            compact = horizontal,
            onClick = onLocate,
        )
        MapActionButton(
            label = "撤销",
            description = "撤销最后一个路线点",
            enabled = hasPoints && routeActionsEnabled,
            compact = horizontal,
            onClick = onUndo,
        )
        Button(
            onClick = onToggleDrawing,
            enabled = routeActionsEnabled,
            modifier = Modifier
                .sizeIn(minWidth = 56.dp, minHeight = 48.dp)
                .semantics {
                    contentDescription = if (state.routeMapState.isDrawing) "结束路线绘制" else "开始路线绘制"
                    stateDescription = if (state.routeMapState.isDrawing) "绘制中" else "未绘制"
                },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.routeMapState.isDrawing) ScoreboardInk else FieldPaper,
                contentColor = if (state.routeMapState.isDrawing) FieldPaper else ScoreboardInk,
            ),
            border = BorderStroke(1.dp, ScoreboardInk),
            shape = MaterialTheme.shapes.medium,
            contentPadding = if (horizontal) {
                PaddingValues(horizontal = 8.dp)
            } else {
                ButtonDefaults.ContentPadding
            },
        ) {
            Text(if (state.routeMapState.isDrawing) "完成" else "绘制")
        }
        MapActionButton(
            label = "清空",
            description = "清空当前路线",
            enabled = hasPoints && routeActionsEnabled,
            contentColor = ScoreboardInk,
            compact = horizontal,
            onClick = onClear,
        )
    }

    if (horizontal) {
        Row(
            modifier = modifier.testTag(MAP_ACTION_RAIL_TAG),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = { actions() },
        )
    } else {
        Column(
            modifier = modifier.testTag(MAP_ACTION_RAIL_TAG),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
            content = { actions() },
        )
    }
}

@Composable
private fun MapActionButton(
    label: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentColor: Color = ScoreboardInk,
    compact: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .sizeIn(minWidth = 56.dp, minHeight = 48.dp)
            .semantics { contentDescription = description },
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = FieldPaper,
            contentColor = contentColor,
        ),
        border = BorderStroke(1.dp, LaneGrayGreen),
        shape = MaterialTheme.shapes.medium,
        contentPadding = if (compact) {
            PaddingValues(horizontal = 8.dp)
        } else {
            ButtonDefaults.ContentPadding
        },
    ) {
        Text(label, maxLines = 1)
    }
}

internal const val MAP_ACTION_RAIL_TAG = "map_action_rail"
private val COMPACT_MAP_HEIGHT = 420.dp
private val HORIZONTAL_MAP_MINIMUM_WIDTH = 520.dp
