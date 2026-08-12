package com.alphaauxiliary.fitgenerator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.alphaauxiliary.fitgenerator.map.CoordinateSystem
import com.alphaauxiliary.fitgenerator.map.MapProvider
import com.alphaauxiliary.fitgenerator.map.ProviderConnectionResult
import com.alphaauxiliary.fitgenerator.map.ProviderKeyPlacement

internal data class MapProviderEditorState(
    val id: String,
    val displayName: String,
    val xyzUrlTemplate: String,
    val keyPlacement: ProviderKeyPlacement,
    val keyName: String,
    val apiKey: String,
    val coordinateSystem: CoordinateSystem,
    val maxZoom: String,
    val attribution: String,
    val geocoderUrl: String,
) {
    override fun toString(): String =
        "MapProviderEditorState(id=$id, displayName=$displayName, " +
            "xyzUrlTemplate=<redacted>, keyPlacement=$keyPlacement, keyName=<redacted>, " +
            "apiKey=<redacted>, coordinateSystem=$coordinateSystem, maxZoom=$maxZoom, " +
            "attribution=<redacted>, geocoderUrl=<redacted>)"

    companion object {
        fun from(provider: MapProvider): MapProviderEditorState = MapProviderEditorState(
            id = provider.id,
            displayName = provider.displayName,
            xyzUrlTemplate = provider.xyzUrlTemplate.orEmpty(),
            keyPlacement = provider.keyPlacement,
            keyName = provider.keyName.orEmpty(),
            apiKey = provider.apiKey.orEmpty(),
            coordinateSystem = provider.coordinateSystem,
            maxZoom = provider.maxZoom.toString(),
            attribution = provider.attribution,
            geocoderUrl = provider.geocoderUrl.orEmpty(),
        )
    }
}

@Composable
internal fun SettingsScreen(
    activeProviderId: String,
    customProviders: List<MapProvider>,
    draft: MapProviderEditorState,
    isSaving: Boolean,
    isTestingConnection: Boolean,
    connectionResult: ProviderConnectionResult?,
    onSelectProvider: (String) -> Unit,
    onAddProvider: () -> Unit,
    onDraftChange: (MapProviderEditorState) -> Unit,
    onSaveProvider: () -> Unit,
    onTestConnection: () -> Unit,
    onRestoreDefault: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var revealKey by rememberSaveable(draft.id) { mutableStateOf(false) }
    val allProviders = listOf(MapProvider.OpenStreetMap) + customProviders
    val activeProviderName = allProviders
        .firstOrNull { it.id == activeProviderId }
        ?.displayName
        ?: draft.displayName.takeUnless { it.isBlank() }
        ?: "新建自定义地图"

    Surface(
        modifier = modifier.fillMaxSize(),
        color = FieldPaper,
        contentColor = ScoreboardInk,
    ) {
        Column(
            modifier = Modifier
            .fillMaxSize()
            .imePadding()
                .safeDrawingPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ScoreboardInk)
                    .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "地图检录台",
                        modifier = Modifier.semantics { heading() },
                        color = FieldPaper,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "当前地图 · $activeProviderName",
                        color = LaneGrayGreen,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(text = "完成", color = FieldPaper)
                }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = 20.dp)
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(LaneGrayGreen),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    SettingsSection(
                        title = "地图源",
                        supportingText = "默认地图无需 Key；自定义地图保留在本机。",
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            allProviders.forEach { provider ->
                                FilterChip(
                                    selected = provider.id == activeProviderId,
                                    onClick = { onSelectProvider(provider.id) },
                                    label = { Text(provider.displayName) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = onAddProvider,
                            enabled = !isSaving && !isTestingConnection,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text("新增自定义地图源")
                        }
                        TechnicalField(
                            value = draft.displayName,
                            onValueChange = {
                                onDraftChange(draft.copy(displayName = it))
                            },
                            label = "地图源名称",
                            singleLine = true,
                        )
                        TechnicalField(
                            value = draft.xyzUrlTemplate,
                            onValueChange = {
                                onDraftChange(draft.copy(xyzUrlTemplate = it))
                            },
                            label = "XYZ URL 模板",
                            supportingText = "必须包含 {z}、{x}、{y}",
                            technical = true,
                        )
                        TechnicalField(
                            value = draft.attribution,
                            onValueChange = {
                                onDraftChange(draft.copy(attribution = it))
                            },
                            label = "地图归属",
                        )
                    }

                    HorizontalDivider(color = LaneGrayGreen)

                    SettingsSection(
                        title = "坐标与缩放",
                        supportingText = "自定义地图必须明确坐标系，轨迹始终以 WGS-84 保存。",
                    ) {
                        Text(
                            text = "坐标系",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CoordinateSystem.entries.forEach { coordinateSystem ->
                                FilterChip(
                                    selected = draft.coordinateSystem == coordinateSystem,
                                    onClick = {
                                        onDraftChange(
                                            draft.copy(coordinateSystem = coordinateSystem),
                                        )
                                    },
                                    label = {
                                        Text(
                                            when (coordinateSystem) {
                                                CoordinateSystem.WGS84 -> "WGS-84"
                                                CoordinateSystem.GCJ02 -> "GCJ-02"
                                                CoordinateSystem.BD09 -> "BD-09"
                                            },
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                )
                            }
                        }
                        TechnicalField(
                            value = draft.maxZoom,
                            onValueChange = { value ->
                                if (value.all(Char::isDigit)) {
                                    onDraftChange(draft.copy(maxZoom = value))
                                }
                            },
                            label = "最大缩放级别",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }

                    HorizontalDivider(color = LaneGrayGreen)

                    SettingsSection(
                        title = "服务凭据",
                        supportingText = "Key 只发送给此地图源，并由 Android Keystore 保护。",
                    ) {
                        Text(
                            text = "Key 放置方式",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        KeyPlacementChoices(
                            selected = draft.keyPlacement,
                            onSelected = { placement ->
                                onDraftChange(draft.copy(keyPlacement = placement))
                            },
                        )
                        if (draft.keyPlacement != ProviderKeyPlacement.NONE) {
                            if (
                                draft.keyPlacement == ProviderKeyPlacement.HEADER ||
                                draft.keyPlacement == ProviderKeyPlacement.QUERY_PARAMETER
                            ) {
                                TechnicalField(
                                    value = draft.keyName,
                                    onValueChange = {
                                        onDraftChange(draft.copy(keyName = it))
                                    },
                                    label = if (draft.keyPlacement == ProviderKeyPlacement.HEADER) {
                                        "Header 名称"
                                    } else {
                                        "查询参数名称"
                                    },
                                    technical = true,
                                    singleLine = true,
                                )
                            }
                            OutlinedTextField(
                                value = draft.apiKey,
                                onValueChange = {
                                    onDraftChange(draft.copy(apiKey = it))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("API Key") },
                                singleLine = true,
                                visualTransformation = if (revealKey) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                ),
                                trailingIcon = {
                                    TextButton(
                                        onClick = { revealKey = !revealKey },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text(if (revealKey) "隐藏" else "显示")
                                    }
                                },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        } else {
                            Text(
                                text = "此地图源不使用 API Key。",
                                color = PaceTeal,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TechnicalField(
                            value = draft.geocoderUrl,
                            onValueChange = {
                                onDraftChange(draft.copy(geocoderUrl = it))
                            },
                            label = "地点搜索服务地址",
                            supportingText = "留空表示此地图源不提供地点搜索",
                            technical = true,
                        )
                    }

                    connectionResult?.let { result ->
                        Text(
                            text = if (result.isSuccess) {
                                "连接成功 · ${result.message}"
                            } else {
                                "连接失败 · ${result.message}"
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            color = if (result.isSuccess) PaceTeal else HeartBerry,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onTestConnection,
                            enabled = !isTestingConnection && !isSaving,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            Text(if (isTestingConnection) "测试中…" else "测试连接")
                        }
                        Button(
                            onClick = onSaveProvider,
                            enabled = !isSaving && !isTestingConnection,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ScoreboardInk,
                                contentColor = FieldPaper,
                            ),
                        ) {
                            Text(if (isSaving) "保存中…" else "保存地图源")
                        }
                    }
                    TextButton(
                        onClick = onRestoreDefault,
                        enabled = !isSaving && !isTestingConnection,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = "恢复默认地图",
                            color = ScoreboardInk,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = "恢复默认只切换到 OpenStreetMap，不会删除已保存的自定义地图源。",
                        color = ScoreboardInk.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    supportingText: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = supportingText,
            color = ScoreboardInk.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodySmall,
        )
        content()
    }
}

@Composable
private fun TechnicalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    technical: Boolean = false,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = supportingText?.let { text ->
            { Text(text) }
        },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        keyboardOptions = keyboardOptions,
        textStyle = if (technical) {
            MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        } else {
            TextStyle.Default
        },
    )
}

@Composable
private fun KeyPlacementChoices(
    selected: ProviderKeyPlacement,
    onSelected: (ProviderKeyPlacement) -> Unit,
) {
    val choices = listOf(
        ProviderKeyPlacement.NONE to "不使用",
        ProviderKeyPlacement.URL_TEMPLATE to "URL 占位符",
        ProviderKeyPlacement.HEADER to "Header（Android 不支持）",
        ProviderKeyPlacement.QUERY_PARAMETER to "查询参数",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.chunked(2).forEach { rowChoices ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowChoices.forEach { (placement, label) ->
                    FilterChip(
                        selected = selected == placement,
                        enabled = placement != ProviderKeyPlacement.HEADER,
                        onClick = { onSelected(placement) },
                        label = { Text(label) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

private val FieldPaper = Color(0xFFF3F6F1)
private val ScoreboardInk = Color(0xFF16221C)
private val PaceTeal = Color(0xFF1D6F78)
private val HeartBerry = Color(0xFFC2385A)
private val LaneGrayGreen = Color(0xFFB8C3B8)
