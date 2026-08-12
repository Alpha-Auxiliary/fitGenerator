package com.alphaauxiliary.fitgenerator.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.alphaauxiliary.fitgenerator.core.ActivityModelDto
import com.alphaauxiliary.fitgenerator.core.ActivitySampleDto
import com.alphaauxiliary.fitgenerator.core.LapModelDto
import com.alphaauxiliary.fitgenerator.ui.theme.FitGeneratorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

internal class MainScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyScreenExposesRouteActionsGuidanceAndExportWorkflow() {
        setMainScreen(state = MainUiState(isInitialized = true))

        composeRule.onNodeWithContentDescription("开始路线绘制").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("撤销最后一个路线点").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("定位到路线起点")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("清空当前路线").assertIsDisplayed()
        composeRule.onNodeWithText("在地图上开始绘制路线").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("展开模拟参数与导出")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithContentDescription("生成活动预览")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("生成 FIT")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun primaryControlsMeetTheFortyEightDpTouchTarget() {
        setMainScreen(state = MainUiState(isInitialized = true))

        listOf(
            "开始路线绘制",
            "撤销最后一个路线点",
            "定位到路线起点",
            "清空当前路线",
            "展开模拟参数与导出",
        ).forEach { description ->
            composeRule.onNodeWithContentDescription(description)
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }

        composeRule.onNodeWithContentDescription("展开模拟参数与导出").performClick()
        composeRule.onNodeWithContentDescription("生成 FIT")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun exportRemainsReachableAtSupportedFontScales() {
        val fontScale = mutableStateOf(1f)
        setMainScreen(
            state = MainUiState(isInitialized = true),
            fontScale = { fontScale.value },
        )
        composeRule.onNodeWithContentDescription("展开模拟参数与导出").performClick()

        listOf(1f, 1.3f, 2f).forEach { scale ->
            composeRule.runOnIdle { fontScale.value = scale }
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("生成 FIT")
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun narrowLandscapeKeepsTheWorkflowScrollable() {
        setMainScreen(
            state = MainUiState(isInitialized = true),
            viewport = DpSize(width = 600.dp, height = 320.dp),
        )

        val actionBounds = composeRule.onNodeWithTag(MAP_ACTION_RAIL_TAG)
            .fetchSemanticsNode().boundsInRoot
        val timingBounds = composeRule.onNodeWithTag(ROUTE_TIMING_STRIP_TAG)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(actionBounds.bottom <= timingBounds.top)
        composeRule.onNodeWithContentDescription("定位到路线起点").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("开始路线绘制").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("展开模拟参数与导出").performClick()
        composeRule.onNodeWithContentDescription("生成 FIT")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun workflowHeadingsFollowSimulationPreviewExportOrder() {
        setMainScreen(state = MainUiState(isInitialized = true))
        composeRule.onNodeWithContentDescription("展开模拟参数与导出").performClick()

        val simulationTop = composeRule.onNodeWithText("1 模拟")
            .fetchSemanticsNode().boundsInRoot.top
        val previewTop = composeRule.onNodeWithText("2 预览")
            .fetchSemanticsNode().boundsInRoot.top
        val exportTop = composeRule.onNodeWithText("3 导出")
            .fetchSemanticsNode().boundsInRoot.top

        assertTrue(simulationTop < previewTop)
        assertTrue(previewTop < exportTop)
    }

    @Test
    fun drawingStateIsAnnouncedWithTextAndSemantics() {
        val state = MainUiState(
            phase = MainPhase.DRAWING,
            isInitialized = true,
            routeMapState = MainUiState().routeMapState.copy(isDrawing = true),
        )
        setMainScreen(state = state)

        composeRule.onNodeWithContentDescription("结束路线绘制")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "绘制中",
                ),
            )
        composeRule.onNodeWithText("路线绘制中 · 点间至少 8 米").assertIsDisplayed()
    }

    @Test
    fun timingStripReadsTimePaceHeartAndLapFromTheCanonicalPreviewSample() {
        val preview = ActivityModelDto(
            schemaVersion = 1,
            algorithmVersion = 1,
            startTimeUtc = "2026-08-08T00:00:00.000Z",
            seed = 42uL,
            totalDistanceCm = 14_000,
            totalDurationMs = 60_000,
            laps = listOf(
                LapModelDto(
                    index = 0,
                    startSample = 0,
                    endSample = 1,
                    distanceCm = 14_000,
                    durationMs = 60_000,
                ),
            ),
            samples = listOf(
                previewSample(timeMs = 0, distanceCm = 0, heartRate = 120),
                previewSample(timeMs = 60_000, distanceCm = 14_000, heartRate = 150),
            ),
        )
        val metrics = MainUiState(
            phase = MainPhase.READY,
            isInitialized = true,
            preview = preview,
            previewSampleIndex = 999,
        ).toTimingStripMetrics()

        assertEquals("0.14 km", metrics.distance)
        assertEquals("01:00", metrics.time)
        assertEquals("8'20\"/km", metrics.pace)
        assertEquals("150 bpm", metrics.heartRate)
        assertEquals("1/1", metrics.lap)
    }

    @Test
    fun invalidPreviewMetricsUsePlaceholdersInsteadOfConfiguredFallbacks() {
        val preview = ActivityModelDto(
            schemaVersion = 1,
            algorithmVersion = 1,
            startTimeUtc = "2026-08-08T00:00:00.000Z",
            seed = 42uL,
            totalDistanceCm = 0,
            totalDurationMs = 0,
            laps = emptyList(),
            samples = listOf(
                previewSample(timeMs = 0, distanceCm = 0, heartRate = 120, speed = 0),
            ),
        )

        val metrics = MainUiState(
            phase = MainPhase.READY,
            isInitialized = true,
            preview = preview,
            previewSampleIndex = 0,
        ).toTimingStripMetrics()

        assertEquals("--", metrics.pace)
        assertEquals("--", metrics.lap)
    }

    @Test
    fun busyStateDisablesParameterAndWorkflowActions() {
        setMainScreen(
            state = MainUiState(
                phase = MainPhase.PREVIEWING,
                isInitialized = true,
            ),
        )
        composeRule.onNodeWithContentDescription("展开模拟参数与导出").performClick()

        composeRule.onNodeWithContentDescription("提高目标配速五秒")
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("生成活动预览")
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("生成 FIT")
            .performScrollTo()
            .assertIsNotEnabled()
    }

    private fun setMainScreen(
        state: MainUiState,
        fontScale: () -> Float = { 1f },
        viewport: DpSize? = null,
    ) {
        composeRule.setContent {
            val deviceDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    deviceDensity.density,
                    fontScale(),
                ),
            ) {
                FitGeneratorTheme {
                    MainScreenForTest(
                        state = state,
                        modifier = viewport?.let { size ->
                            Modifier.requiredSize(size.width, size.height)
                        } ?: Modifier,
                    )
                }
            }
        }
    }

    private fun previewSample(
        timeMs: Long,
        distanceCm: Long,
        heartRate: Int,
        speed: Int = 2_000,
    ): ActivitySampleDto = ActivitySampleDto(
        timeMs = timeMs,
        distanceCm = distanceCm,
        speedMmPerSec = speed,
        heartRateBpm = heartRate,
        positionLatSemicircles = 476_204_329,
        positionLongSemicircles = 1_388_819_573,
    )

    @Composable
    private fun MainScreenForTest(
        state: MainUiState,
        modifier: Modifier = Modifier,
    ) {
        MainScreen(
            state = state,
            onSearchQueryChanged = {},
            onSearchResultSelected = {},
            onOpenSettings = {},
            onToggleDrawing = {},
            onUndo = {},
            onClear = {},
            onLocate = {},
            onPreview = {},
            onExport = {},
            onPaceChanged = {},
            onHeartRatesChanged = { _, _ -> },
            onLapCountChanged = {},
            onExportCountChanged = {},
            onPreviewSampleIndexChanged = {},
            onDismissError = {},
            onDrawPoint = {},
            onSelectVertex = {},
            onMoveSelectedVertex = {},
            onMapCameraChanged = { _, _ -> },
            onMapError = {},
            mapContent = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "测试地图" },
                )
            },
            modifier = modifier,
        )
    }
}
