package com.alphaauxiliary.fitgenerator

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.alphaauxiliary.fitgenerator.core.NativeCoreService
import com.alphaauxiliary.fitgenerator.data.RouteRepository
import com.alphaauxiliary.fitgenerator.data.SettingsRepository
import com.alphaauxiliary.fitgenerator.export.ExportCoordinator
import com.alphaauxiliary.fitgenerator.export.ExportDocumentRequest
import com.alphaauxiliary.fitgenerator.export.ExportException
import com.alphaauxiliary.fitgenerator.map.LocationSearchService
import com.alphaauxiliary.fitgenerator.ui.MainScreen
import com.alphaauxiliary.fitgenerator.ui.MainSettingsDraft
import com.alphaauxiliary.fitgenerator.ui.MainViewModel
import com.alphaauxiliary.fitgenerator.ui.NativeActivityPreviewer
import com.alphaauxiliary.fitgenerator.ui.ServiceLocationSearcher
import com.alphaauxiliary.fitgenerator.ui.SettingsScreen
import com.alphaauxiliary.fitgenerator.ui.theme.FitGeneratorTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var mainViewModel: MainViewModel
    private var activeExportRequestId: Long? = null

    private val createFitDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(ExportCoordinator.FIT_MIME_TYPE),
    ) { uri ->
        completeDocumentSelection(uri)
    }

    private val createZipDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(ExportCoordinator.ZIP_MIME_TYPE),
    ) { uri ->
        completeDocumentSelection(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeExportRequestId = savedInstanceState
            ?.takeIf { it.containsKey(ACTIVE_EXPORT_REQUEST_ID_KEY) }
            ?.getLong(ACTIVE_EXPORT_REQUEST_ID_KEY)
        mainViewModel = ViewModelProvider(
            this,
            MainViewModelFactory(applicationContext),
        )[MainViewModel::class.java]

        lifecycleScope.launch {
            try {
                mainViewModel.deleteStaleShareExports()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A stale cache cleanup failure must not block launch or expose file paths.
            }
        }
        observeExportRequests()

        setContent {
            val state by mainViewModel.state.collectAsStateWithLifecycle()
            val settingsState by mainViewModel.settingsState.collectAsStateWithLifecycle()
            var settingsApiKeyInput by remember(settingsState.draftSessionId) {
                mutableStateOf("")
            }
            FitGeneratorTheme {
                BackHandler(enabled = settingsState.isOpen) {
                    mainViewModel.closeSettings()
                }
                if (settingsState.isOpen) {
                    DisposableEffect(settingsState.draftSessionId) {
                        onDispose {
                            mainViewModel.cancelSettingsConnectionTest()
                        }
                    }
                    SettingsScreen(
                        activeProviderId = settingsState.activeProviderId,
                        customProviders = settingsState.customProviders,
                        draft = settingsState.draft.toEditor(settingsApiKeyInput),
                        isSaving = settingsState.isSaving,
                        isTestingConnection = settingsState.isTestingConnection,
                        connectionResult = settingsState.connectionResult,
                        onSelectProvider = mainViewModel::selectSettingsProvider,
                        onAddProvider = mainViewModel::addCustomMapProvider,
                        onDraftChange = { updatedDraft ->
                            settingsApiKeyInput = updatedDraft.apiKey
                            mainViewModel.updateSettingsDraft(
                                MainSettingsDraft.from(updatedDraft),
                            )
                        },
                        onSaveProvider = {
                            mainViewModel.saveSettingsProvider(settingsApiKeyInput)
                        },
                        onTestConnection = {
                            mainViewModel.testSettingsConnection(settingsApiKeyInput)
                        },
                        onRestoreDefault = mainViewModel::restoreDefaultMapProvider,
                        onClose = mainViewModel::closeSettings,
                    )
                } else {
                    MainScreen(
                        state = state,
                        onSearchQueryChanged = mainViewModel::updateSearchQuery,
                        onSearchResultSelected = mainViewModel::selectSearchResult,
                        onOpenSettings = mainViewModel::openSettings,
                        onToggleDrawing = {
                            mainViewModel.setDrawingEnabled(!state.routeMapState.isDrawing)
                        },
                        onUndo = mainViewModel::undoRoutePoint,
                        onClear = mainViewModel::clearRoute,
                        onLocate = {
                            state.routeMapState.canonicalWgs84Points.firstOrNull()
                                ?.let(mainViewModel::locateAt)
                        },
                        onPreview = mainViewModel::previewRoute,
                        onExport = mainViewModel::exportPreview,
                        onPaceChanged = mainViewModel::updatePace,
                        onHeartRatesChanged = mainViewModel::updateHeartRates,
                        onLapCountChanged = mainViewModel::updateLapCount,
                        onExportCountChanged = mainViewModel::updateExportCount,
                        onPreviewSampleIndexChanged = mainViewModel::updatePreviewSampleIndex,
                        onDismissError = mainViewModel::dismissError,
                        onDrawPoint = mainViewModel::addDrawnPoint,
                        onSelectVertex = mainViewModel::selectVertex,
                        onMoveSelectedVertex = mainViewModel::moveSelectedVertex,
                        onMapCameraChanged = mainViewModel::updateMapCamera,
                        onMapError = mainViewModel::reportMapError,
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        activeExportRequestId?.let { requestId ->
            outState.putLong(ACTIVE_EXPORT_REQUEST_ID_KEY, requestId)
        }
        super.onSaveInstanceState(outState)
    }

    private fun observeExportRequests() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.pendingExportDocument.collect { request ->
                    if (request != null) launchDocumentPicker(request)
                }
            }
        }
    }

    private fun launchDocumentPicker(request: ExportDocumentRequest) {
        if (!mainViewModel.markExportDocumentPickerLaunched(request.requestId)) return
        activeExportRequestId = request.requestId
        try {
            when (request.mimeType) {
                ExportCoordinator.FIT_MIME_TYPE -> {
                    createFitDocument.launch(request.suggestedFileName)
                }
                ExportCoordinator.ZIP_MIME_TYPE -> {
                    createZipDocument.launch(request.suggestedFileName)
                }
                else -> {
                    mainViewModel.failExportDocumentLaunch(request.requestId)
                    activeExportRequestId = null
                }
            }
        } catch (_: ActivityNotFoundException) {
            launchShareFallback(request.requestId)
        } catch (_: SecurityException) {
            launchShareFallback(request.requestId)
        }
    }

    private fun completeDocumentSelection(uri: Uri?) {
        val requestId = activeExportRequestId
            ?: UNKNOWN_EXPORT_REQUEST_ID
        activeExportRequestId = null
        mainViewModel.completeExportDocument(requestId, uri?.toString())
    }

    private fun launchShareFallback(requestId: Long) {
        lifecycleScope.launch {
            try {
                val share = mainViewModel.prepareExportShareFallback(requestId)
                val contentUri = Uri.parse(share.contentUri)
                if (contentUri.scheme != CONTENT_RESOLVER_SCHEME) {
                    throw SecurityException("Unexpected share URI scheme")
                }
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = share.mimeType
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    clipData = ClipData.newUri(contentResolver, share.fileName, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(sendIntent, "分享 ${share.fileName}"))
                mainViewModel.completeExportShare(requestId)
                activeExportRequestId = null
            } catch (cancelled: CancellationException) {
                mainViewModel.failExportDocumentLaunch(requestId)
                activeExportRequestId = null
                throw cancelled
            } catch (error: ExportException) {
                mainViewModel.failExportDocumentLaunch(requestId, error.message)
                activeExportRequestId = null
            } catch (_: Exception) {
                mainViewModel.failExportDocumentLaunch(requestId)
                activeExportRequestId = null
            }
        }
    }

    private class MainViewModelFactory(
        private val applicationContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MainViewModel::class.java))
            val coreService = NativeCoreService()
            val viewModel = MainViewModel(
                core = NativeActivityPreviewer(coreService),
                settingsRepository = SettingsRepository(applicationContext),
                routeRepository = RouteRepository(applicationContext),
                locationSearch = ServiceLocationSearcher(LocationSearchService()),
                exportCoordinator = ExportCoordinator(applicationContext, coreService),
            )
            return viewModel as T
        }
    }

    private companion object {
        const val ACTIVE_EXPORT_REQUEST_ID_KEY = "active-export-request-id"
        const val UNKNOWN_EXPORT_REQUEST_ID = Long.MIN_VALUE
        const val CONTENT_RESOLVER_SCHEME = "content"
    }
}
