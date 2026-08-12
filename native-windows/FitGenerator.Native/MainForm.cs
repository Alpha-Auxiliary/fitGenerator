using System.Globalization;
using FitGenerator.Native.Core;
using FitGenerator.Native.Map;
using FitGenerator.Native.Services;
using FitGenerator.Native.UI;
using Mapsui.UI.WindowsForms;
using Color = System.Drawing.Color;
using DrawingSize = System.Drawing.Size;

namespace FitGenerator.Native;

public sealed class MainForm : Form
{
    private readonly MapControl _mapControl = new();
    private readonly RouteTimingStrip _timingStrip = new();
    private readonly Label _status = new();
    private readonly Label _routeSummary = new();
    private readonly Label _providerAttribution = new();
    private readonly TextBox _searchBox = new();
    private readonly TextBox _exportDirectoryBox = new();
    private readonly ComboBox _mapProvider = new();
    private readonly DateTimePicker _startTime = new();
    private readonly NumericUpDown _pace = new();
    private readonly NumericUpDown _hrRest = new();
    private readonly NumericUpDown _hrMax = new();
    private readonly NumericUpDown _laps = new();
    private readonly NumericUpDown _exports = new();
    private readonly CheckBox _drawMode = new();
    private readonly Button _previewButton;
    private readonly Button _exportButton;
    private readonly Button _undoButton;
    private readonly Button _fitButton;
    private readonly Button _clearButton;
    private readonly Button _searchButton;
    private readonly Button _cancelSearchButton;
    private readonly Button _settingsButton;
    private readonly RouteMapController _mapController;
    private readonly SearchService _searchService;
    private readonly MainViewModel _viewModel;
    private CancellationTokenSource? _searchCancellation;
    private string? _offlineMapProviderId;
    private bool _isBindingState;

    public MainForm()
    {
        AutoScaleMode = AutoScaleMode.Dpi;
        DesignTokens.SetBodyFont(this, 9.5f);
        Text = "校园跑 FIT";
        MinimumSize = new DrawingSize(1040, 680);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = DesignTokens.FieldPaper;
        ForeColor = DesignTokens.ScoreboardInk;

        _mapControl.AccessibleName = "路线地图";
        _mapControl.AccessibleDescription = "绘制、查看并调整校园跑路线";
        _mapControl.TabStop = true;
        _mapControl.TabIndex = 2;
        _mapController = new RouteMapController(_mapControl);
        _searchService = new SearchService();
        var coreService = new NativeCoreService();
        _viewModel = new MainViewModel(
            coreService,
            new SettingsService(),
            _mapController,
            new ExportService(coreService),
            new SystemApplicationClock(),
            _searchService);

        _settingsButton = CreateButton(
            "地图设置",
            (_, _) => OpenMapSettings(),
            ButtonTone.Neutral,
            compact: true);
        _previewButton = CreateButton("生成预览", (_, _) =>
        {
            PushActivityParameters();
            _viewModel.Preview();
        }, ButtonTone.PaceOutline);
        _exportButton = CreateButton("生成 FIT", (_, _) =>
        {
            PushActivityParameters();
            PushExportOptions();
            _viewModel.Export();
        }, ButtonTone.Primary);
        _undoButton = CreateButton(
            "撤销一点",
            (_, _) => _viewModel.UndoPoint(),
            ButtonTone.Neutral,
            compact: true);
        _fitButton = CreateButton(
            "定位路线",
            (_, _) => _viewModel.ZoomToRoute(),
            ButtonTone.Neutral,
            compact: true);
        _clearButton = CreateButton(
            "清空路线",
            (_, _) => _viewModel.ClearRoute(),
            ButtonTone.DangerQuiet,
            compact: true);
        _searchButton = CreateButton(
            "搜索",
            async (_, _) => await SearchPlaceAsync(),
            ButtonTone.Ink,
            compact: true);
        _cancelSearchButton = CreateButton(
            "取消",
            (_, _) => CancelSearch(),
            ButtonTone.Neutral,
            compact: true);
        _cancelSearchButton.AccessibleName = "取消地点搜索";
        _cancelSearchButton.Enabled = false;

        ConfigureInputs();
        var root = BuildRootLayout(out var split);
        Controls.Add(root);

        _viewModel.StateChanged += OnStateChanged;
        _mapController.DrawingCompleted += OnDrawingCompleted;
        _mapController.BaseMapAvailabilityChanged += OnBaseMapAvailabilityChanged;
        _timingStrip.PlaybackFrameChanged += OnPlaybackFrameChanged;
        Load += (_, _) => ConfigureScaledLayout(split);
        Shown += OnFirstShown;
        FormClosed += OnFormClosed;

        BindState(_viewModel.State);
    }

    private void ConfigureInputs()
    {
        _mapProvider.DropDownStyle = ComboBoxStyle.DropDownList;
        _mapProvider.AccessibleName = "地图源";
        _mapProvider.TabIndex = 0;
        _mapProvider.SelectedIndexChanged += (_, _) =>
        {
            if (!_isBindingState
                && _mapProvider.SelectedItem is MapProviderDefinition provider)
            {
                _viewModel.ChangeMapProvider(provider);
            }
        };
        StyleInput(_mapProvider);

        _startTime.Format = DateTimePickerFormat.Custom;
        _startTime.CustomFormat = "yyyy-MM-dd  HH:mm";
        _startTime.ShowUpDown = true;
        _startTime.AccessibleName = "活动开始时间";
        _startTime.TabIndex = 0;
        _startTime.ValueChanged += (_, _) => PushActivityParameters();
        StyleInput(_startTime);

        ConfigureNumeric(_pace, 1, 60, 0.05m, 2, "目标配速，分钟每公里");
        _pace.TabIndex = 0;
        _pace.ValueChanged += (_, _) => PushActivityParameters();
        ConfigureNumeric(_hrRest, 30, 120, 1, 0, "静息心率");
        _hrRest.TabIndex = 0;
        _hrRest.ValueChanged += (_, _) => PushActivityParameters();
        ConfigureNumeric(_hrMax, 100, 220, 1, 0, "最大心率");
        _hrMax.TabIndex = 1;
        _hrMax.ValueChanged += (_, _) => PushActivityParameters();
        ConfigureNumeric(_laps, 1, 100, 1, 0, "模拟圈数");
        _laps.TabIndex = 1;
        _laps.ValueChanged += (_, _) => PushActivityParameters();
        ConfigureNumeric(_exports, 1, 20, 1, 0, "导出文件份数");
        _exports.TabIndex = 0;
        _exports.ValueChanged += (_, _) => PushExportOptions();

        _exportDirectoryBox.PlaceholderText = "选择保存文件夹";
        _exportDirectoryBox.AccessibleName = "FIT 保存文件夹";
        _exportDirectoryBox.TabIndex = 0;
        _exportDirectoryBox.Validated += (_, _) => PushExportOptions();
        StyleInput(_exportDirectoryBox);

        _searchBox.PlaceholderText = "搜索学校、操场或道路";
        _searchBox.AccessibleName = "搜索地点";
        _searchBox.KeyDown += async (_, eventArgs) =>
        {
            if (eventArgs.KeyCode == Keys.Escape && _searchCancellation is not null)
            {
                eventArgs.SuppressKeyPress = true;
                CancelSearch();
                return;
            }

            if (eventArgs.KeyCode != Keys.Enter)
            {
                return;
            }

            eventArgs.SuppressKeyPress = true;
            await SearchPlaceAsync();
        };
        StyleInput(_searchBox);

        _drawMode.Appearance = Appearance.Button;
        _drawMode.AutoSize = false;
        _drawMode.Text = "绘制路线";
        _drawMode.TextAlign = ContentAlignment.MiddleCenter;
        _drawMode.FlatStyle = FlatStyle.Flat;
        _drawMode.FlatAppearance.BorderSize = 1;
        _drawMode.MinimumSize = new DrawingSize(96, DesignTokens.MinimumTargetSize);
        _drawMode.Size = new DrawingSize(102, DesignTokens.MinimumTargetSize);
        _drawMode.Margin = new Padding(0, 0, 8, 0);
        DesignTokens.SetBodyFont(_drawMode, 9f, FontStyle.Bold);
        _drawMode.AccessibleName = "开启或关闭路线绘制";
        _drawMode.CheckedChanged += (_, _) =>
        {
            SetDrawModeVisual();
            if (_isBindingState)
            {
                return;
            }

            if (_drawMode.Checked)
            {
                _viewModel.StartDrawing();
            }
            else
            {
                _viewModel.StopDrawing();
            }
        };
        DesignTokens.AttachRoundedRegion(_drawMode, DesignTokens.RadiusSmall);
        SetDrawModeVisual();
    }

    private Control BuildRootLayout(out SplitContainer split)
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 2,
            Margin = Padding.Empty,
            Padding = Padding.Empty,
            BackColor = DesignTokens.FieldPaper,
        };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 62));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.Controls.Add(BuildTopBar(), 0, 0);

        split = new SplitContainer
        {
            Dock = DockStyle.Fill,
            FixedPanel = FixedPanel.Panel1,
            IsSplitterFixed = true,
            SplitterWidth = 1,
            BackColor = DesignTokens.LaneGrayGreen,
            TabStop = false,
        };
        split.Panel1.BackColor = DesignTokens.FieldPaper;
        split.Panel2.BackColor = DesignTokens.ScoreboardInk;
        split.Panel1.Controls.Add(BuildSequencePanel());
        split.Panel2.Controls.Add(BuildMapWorkspace());
        root.Controls.Add(split, 0, 1);
        return root;
    }

    private Control BuildTopBar()
    {
        var bar = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            Padding = new Padding(18, 8, 18, 8),
            BackColor = DesignTokens.FieldPaper,
        };
        bar.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        bar.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 112));
        DesignTokens.AttachBottomSeparator(bar);

        var title = new Label
        {
            Text = "校园跑 FIT",
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft,
            ForeColor = DesignTokens.ScoreboardInk,
            AccessibleRole = AccessibleRole.StaticText,
        };
        DesignTokens.SetDisplayFont(title, 17f, FontStyle.Bold);
        _settingsButton.Dock = DockStyle.Fill;
        _settingsButton.Margin = new Padding(12, 2, 0, 2);
        _settingsButton.TabIndex = 0;
        bar.Controls.Add(title, 0, 0);
        bar.Controls.Add(_settingsButton, 1, 0);
        return bar;
    }

    private Control BuildSequencePanel()
    {
        var shell = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 2,
            BackColor = DesignTokens.FieldPaper,
        };
        shell.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        shell.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        shell.RowStyles.Add(new RowStyle(SizeType.Absolute, 142));
        var content = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            AutoScroll = true,
            ColumnCount = 1,
            RowCount = 0,
            Padding = new Padding(18, 14, 18, 8),
            BackColor = DesignTokens.FieldPaper,
        };
        content.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        AddAutoRow(content, BuildRouteSection());
        AddAutoRow(content, BuildSimulationSection());
        AddAutoRow(content, BuildExportSection());

        var footer = BuildSidebarFooter();
        footer.Dock = DockStyle.Fill;
        shell.Controls.Add(content, 0, 0);
        shell.Controls.Add(footer, 0, 1);
        return shell;
    }

    private Control BuildRouteSection()
    {
        var body = CreateSingleColumnTable();
        AddAutoRow(body, LabeledField("地图源", _mapProvider));
        _routeSummary.AutoSize = false;
        _routeSummary.Height = 42;
        _routeSummary.Dock = DockStyle.Fill;
        _routeSummary.Padding = new Padding(0, 4, 0, 0);
        _routeSummary.ForeColor = DesignTokens.QuietInk;
        DesignTokens.SetBodyFont(_routeSummary, 9f);
        _routeSummary.AccessibleName = "路线状态";
        AddAutoRow(body, _routeSummary);
        return Section(
            "1",
            "路线",
            "选择底图，然后在地图上绘制路线。",
            body);
    }

    private Control BuildSimulationSection()
    {
        var body = CreateSingleColumnTable();
        AddAutoRow(body, LabeledField("开始时间", _startTime));
        AddAutoRow(body, FieldPair(
            LabeledField("配速  分/公里", _pace),
            LabeledField("圈数", _laps)));
        AddAutoRow(body, FieldPair(
            LabeledField("静息心率", _hrRest),
            LabeledField("最大心率", _hrMax)));
        _previewButton.Dock = DockStyle.Top;
        _previewButton.Margin = new Padding(0, 7, 0, 0);
        _previewButton.TabIndex = 4;
        AddAutoRow(body, _previewButton);
        return Section(
            "2",
            "模拟",
            "设置配速、圈数和心率，生成活动预览。",
            body);
    }

    private Control BuildExportSection()
    {
        var body = CreateSingleColumnTable();
        AddAutoRow(body, LabeledField("导出份数", _exports));
        AddAutoRow(body, ExportDirectoryField());
        var hint = new Label
        {
            Text = "先生成预览；导出会复用当前预览结果。",
            AutoSize = true,
            MaximumSize = new DrawingSize(320, 0),
            Margin = new Padding(0, 5, 0, 2),
            ForeColor = DesignTokens.QuietInk,
        };
        DesignTokens.SetBodyFont(hint, 8.5f);
        AddAutoRow(body, hint);
        return Section(
            "3",
            "导出",
            "选择保存位置并生成本地 FIT 文件。",
            body,
            drawSeparator: false);
    }

    private Panel BuildSidebarFooter()
    {
        var footer = new Panel
        {
            Height = 142,
            BackColor = DesignTokens.FieldPaper,
            Padding = new Padding(18, 10, 18, 14),
        };
        DesignTokens.AttachTopSeparator(footer, 18);

        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 2,
            Margin = Padding.Empty,
        };
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 48));
        _status.Dock = DockStyle.Fill;
        _status.Padding = new Padding(0, 2, 0, 6);
        _status.ForeColor = DesignTokens.QuietInk;
        DesignTokens.SetBodyFont(_status, 9f);
        _status.AccessibleRole = AccessibleRole.StatusBar;
        _status.AccessibleName = "当前状态和下一步";
        _exportButton.Dock = DockStyle.Fill;
        _exportButton.Margin = Padding.Empty;
        _exportButton.TabIndex = 0;
        layout.Controls.Add(_status, 0, 0);
        layout.Controls.Add(_exportButton, 0, 1);
        footer.Controls.Add(layout);
        return footer;
    }

    private Control BuildMapWorkspace()
    {
        var workspace = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 2,
            Margin = Padding.Empty,
            Padding = Padding.Empty,
            BackColor = DesignTokens.ScoreboardInk,
        };
        workspace.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        workspace.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        workspace.RowStyles.Add(new RowStyle(SizeType.Absolute, 112));

        var mapSurface = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = DesignTokens.LaneGrayGreen,
            Margin = Padding.Empty,
        };
        _mapControl.Dock = DockStyle.Fill;
        mapSurface.Controls.Add(_mapControl);

        var search = BuildMapSearch();
        var tools = BuildMapTools();
        mapSurface.Controls.Add(search);
        mapSurface.Controls.Add(tools);
        mapSurface.Resize += (_, _) => PositionMapOverlays(mapSurface, search, tools);
        search.BringToFront();
        tools.BringToFront();

        var timingBorder = new Panel
        {
            Dock = DockStyle.Fill,
            Margin = Padding.Empty,
            Padding = new Padding(1),
            BackColor = DesignTokens.ScoreboardInk,
        };
        _timingStrip.Dock = DockStyle.Fill;
        timingBorder.Controls.Add(_timingStrip);
        workspace.Controls.Add(mapSurface, 0, 0);
        workspace.Controls.Add(timingBorder, 0, 1);
        return workspace;
    }

    private Panel BuildMapSearch()
    {
        var shell = new Panel
        {
            BackColor = DesignTokens.FieldPaper,
            Padding = new Padding(10, 8, 10, 7),
            Size = new DrawingSize(640, 72),
            TabIndex = 0,
        };
        DesignTokens.AttachRoundedRegion(shell, DesignTokens.RadiusLarge);
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 3,
            RowCount = 2,
            Margin = Padding.Empty,
            BackColor = DesignTokens.FieldPaper,
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 82));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 74));
        layout.RowStyles.Add(new RowStyle(
            SizeType.Absolute,
            DesignTokens.MinimumTargetSize));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        _searchBox.Dock = DockStyle.Fill;
        _searchBox.Margin = new Padding(0, 0, 8, 0);
        _searchBox.TabIndex = 0;
        _searchButton.Dock = DockStyle.Fill;
        _searchButton.Margin = new Padding(0, 0, 8, 0);
        _searchButton.TabIndex = 1;
        _cancelSearchButton.Dock = DockStyle.Fill;
        _cancelSearchButton.Margin = Padding.Empty;
        _cancelSearchButton.TabIndex = 2;

        _providerAttribution.Dock = DockStyle.Fill;
        _providerAttribution.TextAlign = ContentAlignment.BottomLeft;
        _providerAttribution.ForeColor = DesignTokens.QuietInk;
        DesignTokens.SetBodyFont(_providerAttribution, 8.5f);
        _providerAttribution.AutoEllipsis = true;
        _providerAttribution.AccessibleName = "地图服务归属";
        layout.Controls.Add(_searchBox, 0, 0);
        layout.Controls.Add(_searchButton, 1, 0);
        layout.Controls.Add(_cancelSearchButton, 2, 0);
        layout.Controls.Add(_providerAttribution, 0, 1);
        layout.SetColumnSpan(_providerAttribution, 3);
        shell.Controls.Add(layout);
        return shell;
    }

    private FlowLayoutPanel BuildMapTools()
    {
        var tools = new FlowLayoutPanel
        {
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = false,
            Padding = Padding.Empty,
            Margin = Padding.Empty,
            BackColor = Color.Transparent,
            TabIndex = 1,
        };
        ConfigureMapTool(_undoButton, 94);
        ConfigureMapTool(_fitButton, 94);
        ConfigureMapTool(_clearButton, 94);
        _drawMode.TabIndex = 0;
        _undoButton.TabIndex = 1;
        _fitButton.TabIndex = 2;
        _clearButton.TabIndex = 3;
        tools.Controls.Add(_drawMode);
        tools.Controls.Add(_undoButton);
        tools.Controls.Add(_fitButton);
        tools.Controls.Add(_clearButton);
        return tools;
    }

    private void PositionMapOverlays(Panel surface, Control search, Control tools)
    {
        var padding = LogicalToDeviceUnits(16);
        var gap = LogicalToDeviceUnits(10);
        var maximumSearchWidth = LogicalToDeviceUnits(680);
        var availableSearchWidth = Math.Max(1, surface.ClientSize.Width - padding * 2);
        search.Width = Math.Min(maximumSearchWidth, availableSearchWidth);
        search.Height = LogicalToDeviceUnits(72);
        search.Location = new Point(padding, padding);
        tools.PerformLayout();
        tools.Location = new Point(
            Math.Max(padding, surface.ClientSize.Width - tools.Width - padding),
            search.Bottom + gap);
    }

    private void ConfigureScaledLayout(SplitContainer split)
    {
        MinimumSize = LogicalToDeviceUnits(new DrawingSize(1040, 680));
        var sidebarWidth = LogicalToDeviceUnits(DesignTokens.SidebarWidth);
        split.Panel1MinSize = LogicalToDeviceUnits(330);
        split.Panel2MinSize = LogicalToDeviceUnits(480);
        split.SplitterDistance = Math.Clamp(
            sidebarWidth,
            split.Panel1MinSize,
            Math.Max(split.Panel1MinSize, split.Width - split.Panel2MinSize));
    }

    private Control ExportDirectoryField()
    {
        var field = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 1,
            RowCount = 2,
            Margin = new Padding(0, 3, 0, 4),
        };
        field.RowStyles.Add(new RowStyle(SizeType.Absolute, 20));
        field.RowStyles.Add(new RowStyle(
            SizeType.Absolute,
            DesignTokens.MinimumTargetSize));
        field.Controls.Add(FieldLabel("保存文件夹"), 0, 0);
        var row = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            Margin = Padding.Empty,
        };
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 82));
        _exportDirectoryBox.Dock = DockStyle.Fill;
        _exportDirectoryBox.Margin = new Padding(0, 0, 8, 0);
        var browse = CreateButton(
            "选择",
            (_, _) => BrowseExportDirectory(),
            ButtonTone.Neutral,
            compact: true);
        browse.Dock = DockStyle.Fill;
        browse.Margin = Padding.Empty;
        browse.TabIndex = 1;
        browse.AccessibleName = "选择 FIT 保存文件夹";
        row.Controls.Add(_exportDirectoryBox, 0, 0);
        row.Controls.Add(browse, 1, 0);
        field.Controls.Add(row, 0, 1);
        return field;
    }

    private void PushActivityParameters()
    {
        if (_isBindingState)
        {
            return;
        }

        _viewModel.UpdateActivityParameters(
            _startTime.Value,
            (double)_pace.Value * 60.0,
            (int)_hrRest.Value,
            (int)_hrMax.Value,
            (int)_laps.Value);
    }

    private void PushExportOptions()
    {
        if (_isBindingState)
        {
            return;
        }

        _viewModel.UpdateExportOptions(
            _exportDirectoryBox.Text,
            (int)_exports.Value);
    }

    private void BrowseExportDirectory()
    {
        using var dialog = new FolderBrowserDialog
        {
            Description = "选择 FIT 文件保存文件夹",
            UseDescriptionForTitle = true,
            SelectedPath = Directory.Exists(_exportDirectoryBox.Text.Trim())
                ? _exportDirectoryBox.Text.Trim()
                : Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
        };
        if (dialog.ShowDialog(this) != DialogResult.OK)
        {
            return;
        }

        _exportDirectoryBox.Text = dialog.SelectedPath;
        PushExportOptions();
    }

    private void OpenMapSettings()
    {
        using var dialog = new MapSettingsDialog(
            _viewModel.State.ActiveMapProvider,
            _searchService);
        if (dialog.ShowDialog(this) == DialogResult.OK
            && dialog.SelectedProvider is { } provider)
        {
            _viewModel.ChangeMapProvider(provider);
        }
    }

    private async Task SearchPlaceAsync()
    {
        var currentSearch = new CancellationTokenSource();
        var previousSearch = _searchCancellation;
        _searchCancellation = currentSearch;
        previousSearch?.Cancel();
        _searchButton.Enabled = false;
        _cancelSearchButton.Enabled = true;

        try
        {
            await _viewModel.SearchAsync(
                _searchBox.Text,
                CultureInfo.CurrentUICulture,
                currentSearch.Token);
        }
        finally
        {
            if (ReferenceEquals(_searchCancellation, currentSearch))
            {
                _searchCancellation = null;
                if (!IsDisposed && !Disposing)
                {
                    _searchButton.Enabled = true;
                    _cancelSearchButton.Enabled = false;
                }
            }

            currentSearch.Dispose();
        }
    }

    private void CancelSearch()
    {
        _searchCancellation?.Cancel();
        _searchBox.Focus();
    }

    private void OnDrawingCompleted(
        object? sender,
        RouteDrawingCompletedEventArgs eventArgs)
    {
        _viewModel.FinishDrawing(eventArgs.Wgs84Points);
        _isBindingState = true;
        try
        {
            _drawMode.Checked = false;
            SetDrawModeVisual();
        }
        finally
        {
            _isBindingState = false;
        }
    }

    private void OnBaseMapAvailabilityChanged(
        object? sender,
        BaseMapAvailabilityChangedEventArgs eventArgs)
    {
        if (eventArgs.IsAvailable)
        {
            if (string.Equals(
                    _offlineMapProviderId,
                    eventArgs.ProviderId,
                    StringComparison.Ordinal))
            {
                _offlineMapProviderId = null;
            }
        }
        else
        {
            _offlineMapProviderId = eventArgs.ProviderId;
        }

        UpdateProviderAttribution(_viewModel.State);
    }

    private void OnFormClosed(object? sender, FormClosedEventArgs eventArgs)
    {
        Shown -= OnFirstShown;
        _viewModel.StateChanged -= OnStateChanged;
        _mapController.DrawingCompleted -= OnDrawingCompleted;
        _mapController.BaseMapAvailabilityChanged -= OnBaseMapAvailabilityChanged;
        _timingStrip.PlaybackFrameChanged -= OnPlaybackFrameChanged;
        var currentSearch = _searchCancellation;
        _searchCancellation = null;
        currentSearch?.Cancel();
        currentSearch?.Dispose();
        _viewModel.PersistMapViewportOnClose();
        _searchService.Dispose();
        _mapController.Dispose();
    }

    private void OnFirstShown(object? sender, EventArgs eventArgs)
    {
        Shown -= OnFirstShown;
        // Shown runs after the first layout/handle initialization and before the
        // user can manipulate the camera. Restore synchronously so no later style
        // callback can overwrite an already-interactive viewport.
        _viewModel.RestoreMapViewportOnce();
    }

    private void OnStateChanged(MainViewState state)
    {
        if (IsDisposed || Disposing || !IsHandleCreated)
        {
            return;
        }

        if (InvokeRequired)
        {
            try
            {
                BeginInvoke(new Action(() =>
                {
                    if (!IsDisposed && !Disposing)
                    {
                        BindState(state);
                    }
                }));
            }
            catch (InvalidOperationException) when (
                IsDisposed || Disposing || !IsHandleCreated)
            {
                // The form closed between the guard and BeginInvoke.
            }

            return;
        }

        BindState(state);
    }

    private void OnPlaybackFrameChanged(
        object? sender,
        RouteTimingStripFrameChangedEventArgs eventArgs)
    {
        var sample = eventArgs.Sample;
        _mapController.SetPlaybackMarker(
            sample.PositionLatSemicircles,
            sample.PositionLongSemicircles);
    }

    private void BindState(MainViewState state)
    {
        _isBindingState = true;
        try
        {
            _status.Text = state.StatusMessage;
            _status.ForeColor = state.Phase switch
            {
                MainViewPhase.Error => DesignTokens.HeartBerry,
                MainViewPhase.Previewing => DesignTokens.PaceTeal,
                MainViewPhase.Exporting => DesignTokens.ScoreboardInk,
                _ => DesignTokens.QuietInk,
            };
            _routeSummary.Text = RouteSummary(state);
            _startTime.Value = ClampDateTime(
                state.StartTime,
                _startTime.MinDate,
                _startTime.MaxDate);
            SetNumericValue(_pace, (decimal)(state.PaceSecondsPerKilometer / 60.0));
            SetNumericValue(_hrRest, state.RestingHeartRate);
            SetNumericValue(_hrMax, state.MaximumHeartRate);
            SetNumericValue(_laps, state.LapCount);
            SetNumericValue(_exports, state.ExportCount);
            _exportDirectoryBox.Text = state.ExportDirectory ?? string.Empty;
            BindProviders(state);
            BindTimingStrip(state);

            UpdateProviderAttribution(state);
            _previewButton.Text = state.Phase == MainViewPhase.Previewing
                ? "正在生成预览…"
                : "生成预览";
            _exportButton.Text = state.Phase == MainViewPhase.Exporting
                ? "正在生成 FIT…"
                : "生成 FIT";
            _previewButton.Enabled = state.CanPreview;
            _exportButton.Enabled = state.CanExport;
            _undoButton.Enabled = state.RoutePoints.Count > 0 && !state.IsBusy;
            _fitButton.Enabled = !state.IsBusy;
            _clearButton.Enabled = state.RoutePoints.Count > 0 && !state.IsBusy;
            _drawMode.Enabled = !state.IsBusy;
            _mapProvider.Enabled = !state.IsBusy;
            _settingsButton.Enabled = !state.IsBusy;
        }
        finally
        {
            _isBindingState = false;
        }
    }

    private void UpdateProviderAttribution(MainViewState state)
    {
        _providerAttribution.Text = string.Equals(
            _offlineMapProviderId,
            state.ActiveMapProvider.Id,
            StringComparison.Ordinal)
            ? "离线绘图底板  ·  在线地图将在后台自动重试"
            : $"{state.ActiveMapProvider.DisplayName}  ·  "
                + state.ActiveMapProvider.Attribution;
    }

    private void BindTimingStrip(MainViewState state)
    {
        if (state.Phase == MainViewPhase.Previewing)
        {
            _mapController.ClearPlaybackMarker();
            _timingStrip.SetPlaceholder(RouteTimingStripPlaceholder.Loading);
            return;
        }

        if (state.PreviewModel is not { Samples.Count: > 0 } model)
        {
            _mapController.ClearPlaybackMarker();
            _timingStrip.SetPlaceholder(
                state.Phase == MainViewPhase.Error
                    ? RouteTimingStripPlaceholder.Error
                    : RouteTimingStripPlaceholder.Empty);
            return;
        }

        _timingStrip.Play(model);
    }

    private void BindProviders(MainViewState state)
    {
        var listedProviders = _mapProvider.Items
            .OfType<MapProviderDefinition>()
            .ToArray();
        if (!listedProviders.SequenceEqual(state.AvailableMapProviders))
        {
            _mapProvider.Items.Clear();
            _mapProvider.Items.AddRange(state.AvailableMapProviders.Cast<object>().ToArray());
        }

        var selectedIndex = state.AvailableMapProviders
            .Select((provider, index) => (provider, index))
            .Where(item => string.Equals(
                item.provider.Id,
                state.ActiveMapProvider.Id,
                StringComparison.Ordinal))
            .Select(item => item.index)
            .DefaultIfEmpty(-1)
            .First();
        if (_mapProvider.SelectedIndex != selectedIndex)
        {
            _mapProvider.SelectedIndex = selectedIndex;
        }
    }

    private void SetDrawModeVisual()
    {
        _drawMode.BackColor = _drawMode.Checked
            ? DesignTokens.PaceTeal
            : DesignTokens.FieldPaper;
        _drawMode.ForeColor = _drawMode.Checked
            ? DesignTokens.FieldPaper
            : DesignTokens.ScoreboardInk;
        _drawMode.FlatAppearance.BorderColor = _drawMode.Checked
            ? DesignTokens.PaceTeal
            : DesignTokens.LaneGrayGreen;
        _drawMode.Text = _drawMode.Checked ? "完成绘制" : "绘制路线";
    }

    private static string RouteSummary(MainViewState state) =>
        state.RoutePoints.Count switch
        {
            0 => "尚无路线。点按地图上的“绘制路线”开始。",
            1 => "已有 1 个路线点。再绘制至少 1 点。",
            _ when state.PreviewModel is not null =>
                $"{state.RoutePoints.Count} 个路线点 · 预览已就绪",
            _ => $"{state.RoutePoints.Count} 个路线点 · 可以生成预览",
        };

    private static TableLayoutPanel CreateSingleColumnTable()
    {
        var table = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 1,
            RowCount = 0,
            Margin = Padding.Empty,
        };
        table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        return table;
    }

    private static Control Section(
        string step,
        string title,
        string description,
        Control body,
        bool drawSeparator = true)
    {
        var section = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 1,
            RowCount = 2,
            Margin = new Padding(0, 0, 0, 10),
            Padding = new Padding(0, 0, 0, 12),
            BackColor = DesignTokens.FieldPaper,
        };
        section.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        section.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        section.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        section.Controls.Add(SectionHeader(step, title, description), 0, 0);
        section.Controls.Add(body, 0, 1);
        if (drawSeparator)
        {
            DesignTokens.AttachBottomSeparator(section);
        }

        return section;
    }

    private static Control SectionHeader(string step, string title, string description)
    {
        var header = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 2,
            RowCount = 2,
            Margin = new Padding(0, 0, 0, 7),
        };
        header.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 34));
        header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        var stepLabel = new Label
        {
            Text = step,
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.TopLeft,
            ForeColor = DesignTokens.ScoreboardInk,
        };
        DesignTokens.SetMetricFont(stepLabel, 9f, FontStyle.Bold);
        var titleLabel = new Label
        {
            Text = title,
            Dock = DockStyle.Fill,
            AutoSize = true,
            ForeColor = DesignTokens.ScoreboardInk,
        };
        DesignTokens.SetDisplayFont(titleLabel, 13f, FontStyle.Bold);
        var descriptionLabel = new Label
        {
            Text = description,
            Dock = DockStyle.Fill,
            AutoSize = true,
            MaximumSize = new DrawingSize(300, 0),
            ForeColor = DesignTokens.QuietInk,
        };
        DesignTokens.SetBodyFont(descriptionLabel, 8.5f);
        header.Controls.Add(stepLabel, 0, 0);
        header.SetRowSpan(stepLabel, 2);
        header.Controls.Add(titleLabel, 1, 0);
        header.Controls.Add(descriptionLabel, 1, 1);
        return header;
    }

    private static Control LabeledField(string label, Control editor)
    {
        var field = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 1,
            RowCount = 2,
            Margin = new Padding(0, 2, 0, 4),
        };
        field.RowStyles.Add(new RowStyle(SizeType.Absolute, 20));
        field.RowStyles.Add(new RowStyle(
            SizeType.Absolute,
            DesignTokens.MinimumTargetSize));
        editor.Dock = DockStyle.Fill;
        editor.Margin = Padding.Empty;
        editor.TabIndex = 0;
        field.Controls.Add(FieldLabel(label), 0, 0);
        field.Controls.Add(editor, 0, 1);
        return field;
    }

    private static Control FieldPair(Control left, Control right)
    {
        var row = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 2,
            RowCount = 1,
            Margin = Padding.Empty,
        };
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        left.Dock = DockStyle.Fill;
        left.Margin = new Padding(0, 0, 5, 0);
        left.TabIndex = 0;
        right.Dock = DockStyle.Fill;
        right.Margin = new Padding(5, 0, 0, 0);
        right.TabIndex = 1;
        row.Controls.Add(left, 0, 0);
        row.Controls.Add(right, 1, 0);
        return row;
    }

    private static Label FieldLabel(string text)
    {
        var label = new Label
        {
            Text = text,
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft,
            ForeColor = DesignTokens.QuietInk,
        };
        DesignTokens.SetBodyFont(label, 8.5f, FontStyle.Bold);
        return label;
    }

    private static void AddAutoRow(TableLayoutPanel table, Control control)
    {
        var row = table.RowCount;
        table.RowCount++;
        table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        control.Dock = DockStyle.Top;
        control.TabIndex = row;
        table.Controls.Add(control, 0, row);
    }

    private static Button CreateButton(
        string text,
        EventHandler onClick,
        ButtonTone tone,
        bool compact = false)
    {
        var button = new Button
        {
            Text = text,
            AutoSize = false,
            Height = compact ? DesignTokens.MinimumTargetSize : 44,
            MinimumSize = new DrawingSize(72, DesignTokens.MinimumTargetSize),
            FlatStyle = FlatStyle.Flat,
            UseVisualStyleBackColor = false,
            Cursor = Cursors.Hand,
            AccessibleName = text,
        };
        if (tone == ButtonTone.Primary)
        {
            DesignTokens.SetDisplayFont(button, 14f, FontStyle.Bold);
        }
        else
        {
            DesignTokens.SetBodyFont(button, 9f, FontStyle.Bold);
        }
        ApplyButtonTone(button, tone);
        button.Click += onClick;
        DesignTokens.AttachRoundedRegion(button, DesignTokens.RadiusSmall);
        return button;
    }

    private static void ApplyButtonTone(Button button, ButtonTone tone)
    {
        var (background, foreground, border) = tone switch
        {
            ButtonTone.Primary => (
                DesignTokens.TrackRed,
                DesignTokens.FieldPaper,
                DesignTokens.TrackRed),
            ButtonTone.Ink => (
                DesignTokens.ScoreboardInk,
                DesignTokens.FieldPaper,
                DesignTokens.ScoreboardInk),
            ButtonTone.PaceOutline => (
                DesignTokens.FieldPaper,
                DesignTokens.PaceTeal,
                DesignTokens.PaceTeal),
            ButtonTone.DangerQuiet => (
                DesignTokens.FieldPaper,
                DesignTokens.HeartBerry,
                DesignTokens.HeartBerry),
            _ => (
                DesignTokens.PaperRaised,
                DesignTokens.ScoreboardInk,
                DesignTokens.QuietInk),
        };
        button.BackColor = background;
        button.ForeColor = foreground;
        button.FlatAppearance.BorderColor = border;
        button.FlatAppearance.BorderSize = 1;
        button.FlatAppearance.MouseOverBackColor = tone switch
        {
            ButtonTone.Primary => DesignTokens.TrackRed,
            ButtonTone.Ink => DesignTokens.ScoreboardInk,
            ButtonTone.PaceOutline => DesignTokens.LaneSoft,
            _ => DesignTokens.LaneSoft,
        };
    }

    private static void ConfigureMapTool(Button button, int width)
    {
        button.Size = new DrawingSize(width, DesignTokens.MinimumTargetSize);
        button.Margin = new Padding(0, 0, 8, 0);
    }

    private static void ConfigureNumeric(
        NumericUpDown control,
        decimal minimum,
        decimal maximum,
        decimal increment,
        int decimalPlaces,
        string accessibleName)
    {
        control.Minimum = minimum;
        control.Maximum = maximum;
        control.Increment = increment;
        control.DecimalPlaces = decimalPlaces;
        control.AccessibleName = accessibleName;
        StyleInput(control);
    }

    private static void StyleInput(Control editor)
    {
        DesignTokens.SetBodyFont(editor, 9f);
        editor.ForeColor = DesignTokens.ScoreboardInk;
        editor.BackColor = DesignTokens.PaperRaised;
        editor.Margin = Padding.Empty;
        editor.MinimumSize = new DrawingSize(0, DesignTokens.MinimumTargetSize);
        if (editor is TextBox textBox)
        {
            textBox.BorderStyle = BorderStyle.FixedSingle;
        }
        else if (editor is ComboBox comboBox)
        {
            comboBox.FlatStyle = FlatStyle.Flat;
        }
        else if (editor is NumericUpDown numeric)
        {
            numeric.BorderStyle = BorderStyle.FixedSingle;
        }
    }

    private static void SetNumericValue(NumericUpDown control, decimal value)
    {
        control.Value = Math.Clamp(value, control.Minimum, control.Maximum);
    }

    private static DateTime ClampDateTime(DateTime value, DateTime minimum, DateTime maximum) =>
        value < minimum ? minimum : value > maximum ? maximum : value;

    private enum ButtonTone
    {
        Neutral,
        Ink,
        PaceOutline,
        Primary,
        DangerQuiet,
    }

    private sealed class MapSettingsDialog : Form
    {
        private readonly MapProviderDefinition _initialProvider;
        private readonly ISearchService _searchService;
        private readonly TextBox _name = new();
        private readonly TextBox _tileUrl = new();
        private readonly ComboBox _keyPlacement = new();
        private readonly TextBox _keyName = new();
        private readonly TextBox _apiKey = new();
        private readonly ComboBox _coordinateSystem = new();
        private readonly NumericUpDown _maximumZoom = new();
        private readonly TextBox _attribution = new();
        private readonly TextBox _geocoderUrl = new();
        private readonly Label _testStatus = new();
        private readonly Button _testButton;
        private CancellationTokenSource? _testCancellation;

        internal MapSettingsDialog(
            MapProviderDefinition provider,
            ISearchService searchService)
        {
            _initialProvider = provider;
            _searchService = searchService;
            AutoScaleMode = AutoScaleMode.Dpi;
            DesignTokens.SetBodyFont(this, 9.5f);
            Text = "地图设置";
            StartPosition = FormStartPosition.CenterParent;
            MinimumSize = new DrawingSize(620, 660);
            Size = new DrawingSize(660, 720);
            BackColor = DesignTokens.FieldPaper;
            ForeColor = DesignTokens.ScoreboardInk;
            ShowInTaskbar = false;

            _testButton = DialogButton(
                "测试连接",
                async (_, _) => await TestProviderAsync(),
                emphasized: true);
            Controls.Add(BuildDialogContent());
            Populate(provider);
            SubscribeToConfigurationChanges();
            FormClosed += (_, _) =>
            {
                var cancellation = _testCancellation;
                _testCancellation = null;
                cancellation?.Cancel();
                cancellation?.Dispose();
            };
        }

        internal MapProviderDefinition? SelectedProvider { get; private set; }

        private Control BuildDialogContent()
        {
            var root = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 1,
                RowCount = 3,
                Padding = new Padding(24, 20, 24, 20),
                BackColor = DesignTokens.FieldPaper,
            };
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 52));
            var heading = new Label
            {
                Text = "地图与搜索服务",
                AutoSize = true,
                Margin = new Padding(0, 0, 0, 4),
                ForeColor = DesignTokens.ScoreboardInk,
            };
            DesignTokens.SetDisplayFont(heading, 16f, FontStyle.Bold);
            root.Controls.Add(heading, 0, 0);

            var fields = CreateSingleColumnTable();
            fields.AutoSize = false;
            fields.AutoScroll = true;
            fields.Dock = DockStyle.Fill;
            fields.Padding = new Padding(0, 8, 6, 8);
            ConfigureDialogInputs();
            AddAutoRow(fields, LabeledField("地图源名称", _name));
            AddAutoRow(fields, LabeledField("XYZ URL 模板", _tileUrl));
            AddAutoRow(fields, FieldPair(
                LabeledField("Key 放置位置", _keyPlacement),
                LabeledField("Key 名称", _keyName)));
            AddAutoRow(fields, LabeledField("Key 值（输入时隐藏）", _apiKey));
            AddAutoRow(fields, FieldPair(
                LabeledField("坐标系", _coordinateSystem),
                LabeledField("最大缩放级别", _maximumZoom)));
            AddAutoRow(fields, LabeledField("服务归属", _attribution));
            AddAutoRow(fields, LabeledField("地点搜索服务地址", _geocoderUrl));
            _testStatus.AutoSize = false;
            _testStatus.Height = 42;
            _testStatus.Dock = DockStyle.Top;
            _testStatus.Padding = new Padding(0, 6, 0, 0);
            _testStatus.ForeColor = DesignTokens.QuietInk;
            _testStatus.Text = "修改后先测试连接；Key 默认隐藏。";
            _testStatus.AccessibleName = "地图连接测试结果";
            _testStatus.AccessibleRole = AccessibleRole.StatusBar;
            AddAutoRow(fields, _testStatus);
            root.Controls.Add(fields, 0, 1);

            var actions = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 4,
                Margin = Padding.Empty,
            };
            actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            actions.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 124));
            actions.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 92));
            actions.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 92));
            var restore = DialogButton("恢复默认地图", (_, _) => RestoreDefault());
            var cancel = DialogButton("取消", (_, _) => Close());
            cancel.DialogResult = DialogResult.Cancel;
            var apply = DialogButton("应用", (_, _) => ApplyProvider(), emphasized: true);
            _testButton.Dock = DockStyle.Fill;
            restore.Dock = DockStyle.Fill;
            cancel.Dock = DockStyle.Fill;
            apply.Dock = DockStyle.Fill;
            _testButton.Margin = new Padding(0, 5, 8, 0);
            restore.Margin = new Padding(0, 5, 8, 0);
            cancel.Margin = new Padding(0, 5, 8, 0);
            apply.Margin = new Padding(0, 5, 0, 0);
            _testButton.TabIndex = 0;
            restore.TabIndex = 1;
            cancel.TabIndex = 2;
            apply.TabIndex = 3;
            actions.Controls.Add(_testButton, 0, 0);
            actions.Controls.Add(restore, 1, 0);
            actions.Controls.Add(cancel, 2, 0);
            actions.Controls.Add(apply, 3, 0);
            root.Controls.Add(actions, 0, 2);
            AcceptButton = apply;
            CancelButton = cancel;
            return root;
        }

        private void ConfigureDialogInputs()
        {
            StyleInput(_name);
            _name.AccessibleName = "地图源名称";
            StyleInput(_tileUrl);
            _tileUrl.AccessibleName = "XYZ URL 模板";
            _keyPlacement.DropDownStyle = ComboBoxStyle.DropDownList;
            _keyPlacement.Items.AddRange(new object[]
            {
                new PlacementChoice("不使用 Key", ProviderKeyPlacement.None),
                new PlacementChoice("URL 占位符 {key}", ProviderKeyPlacement.UrlTemplate),
                new PlacementChoice("请求 Header", ProviderKeyPlacement.Header),
                new PlacementChoice("查询参数", ProviderKeyPlacement.QueryParameter),
            });
            _keyPlacement.SelectedIndexChanged += (_, _) => UpdateKeyFields();
            _keyPlacement.AccessibleName = "Key 放置位置";
            StyleInput(_keyPlacement);
            StyleInput(_keyName);
            _keyName.AccessibleName = "Key 名称";
            StyleInput(_apiKey);
            _apiKey.UseSystemPasswordChar = true;
            _apiKey.AccessibleName = "隐藏的 API Key";
            _coordinateSystem.DropDownStyle = ComboBoxStyle.DropDownList;
            _coordinateSystem.Items.AddRange(new object[]
            {
                new CoordinateChoice("WGS-84", CoordinateSystem.Wgs84),
                new CoordinateChoice("GCJ-02", CoordinateSystem.Gcj02),
                new CoordinateChoice("BD-09", CoordinateSystem.Bd09),
            });
            _coordinateSystem.AccessibleName = "地图坐标系";
            StyleInput(_coordinateSystem);
            ConfigureNumeric(_maximumZoom, 0, 30, 1, 0, "最大缩放级别");
            StyleInput(_attribution);
            _attribution.AccessibleName = "地图服务归属";
            StyleInput(_geocoderUrl);
            _geocoderUrl.AccessibleName = "地点搜索服务地址";
        }

        private void SubscribeToConfigurationChanges()
        {
            _name.TextChanged += OnConfigurationChanged;
            _tileUrl.TextChanged += OnConfigurationChanged;
            _keyPlacement.SelectedIndexChanged += OnConfigurationChanged;
            _keyName.TextChanged += OnConfigurationChanged;
            _apiKey.TextChanged += OnConfigurationChanged;
            _coordinateSystem.SelectedIndexChanged += OnConfigurationChanged;
            _maximumZoom.ValueChanged += OnConfigurationChanged;
            _attribution.TextChanged += OnConfigurationChanged;
            _geocoderUrl.TextChanged += OnConfigurationChanged;
        }

        private void OnConfigurationChanged(object? sender, EventArgs eventArgs)
        {
            var cancellation = _testCancellation;
            if (cancellation is null)
            {
                return;
            }

            _testCancellation = null;
            cancellation.Cancel();
            if (!IsDisposed && !Disposing)
            {
                _testButton.Enabled = true;
                ShowTestStatus("配置已修改，请重新测试连接。", success: null);
            }
        }

        private void Populate(MapProviderDefinition provider)
        {
            _name.Text = provider.DisplayName;
            _tileUrl.Text = provider.XyzUrlTemplate;
            SelectChoice<PlacementChoice, ProviderKeyPlacement>(
                _keyPlacement,
                provider.KeyPlacement,
                choice => choice.Value);
            _keyName.Text = provider.KeyName ?? string.Empty;
            _apiKey.Text = provider.ApiKey ?? string.Empty;
            SelectChoice<CoordinateChoice, CoordinateSystem>(
                _coordinateSystem,
                provider.CoordinateSystem,
                choice => choice.Value);
            _maximumZoom.Value = Math.Clamp(
                provider.MaximumZoom,
                (int)_maximumZoom.Minimum,
                (int)_maximumZoom.Maximum);
            _attribution.Text = provider.Attribution;
            _geocoderUrl.Text = provider.GeocoderUrl ?? string.Empty;
            UpdateKeyFields();
        }

        private async Task TestProviderAsync()
        {
            MapProviderDefinition provider;
            try
            {
                provider = BuildProvider();
            }
            catch (InvalidOperationException exception)
            {
                ShowTestStatus(exception.Message, success: false);
                return;
            }

            var currentTest = new CancellationTokenSource();
            var previousTest = _testCancellation;
            _testCancellation = currentTest;
            previousTest?.Cancel();
            _testButton.Enabled = false;
            ShowTestStatus("正在测试地图与搜索服务…", success: null);
            try
            {
                var result = await _searchService.TestConnectionAsync(
                    provider,
                    currentTest.Token);
                if (ReferenceEquals(_testCancellation, currentTest)
                    && !IsDisposed
                    && !Disposing)
                {
                    ShowTestStatus(result.Message, result.IsSuccess);
                }
            }
            catch (OperationCanceledException) when (currentTest.IsCancellationRequested)
            {
                if (ReferenceEquals(_testCancellation, currentTest)
                    && !IsDisposed
                    && !Disposing)
                {
                    ShowTestStatus("连接测试已取消。修改配置后可以重新测试。", success: null);
                }
            }
            catch (Exception)
            {
                if (ReferenceEquals(_testCancellation, currentTest)
                    && !IsDisposed
                    && !Disposing)
                {
                    ShowTestStatus("连接测试失败。请检查地址、Key 和网络后重试。", success: false);
                }
            }
            finally
            {
                if (ReferenceEquals(_testCancellation, currentTest))
                {
                    _testCancellation = null;
                    if (!IsDisposed && !Disposing)
                    {
                        _testButton.Enabled = true;
                    }
                }

                currentTest.Dispose();
            }
        }

        private void ApplyProvider()
        {
            try
            {
                SelectedProvider = BuildProvider();
                DialogResult = DialogResult.OK;
                Close();
            }
            catch (InvalidOperationException exception)
            {
                ShowTestStatus(exception.Message, success: false);
            }
        }

        private void RestoreDefault()
        {
            SelectedProvider = MapProviderDefinition.OpenStreetMap;
            DialogResult = DialogResult.OK;
            Close();
        }

        private MapProviderDefinition BuildProvider()
        {
            var name = _name.Text.Trim();
            var tileUrl = _tileUrl.Text.Trim();
            var attribution = _attribution.Text.Trim();
            var geocoderUrl = NullIfWhiteSpace(_geocoderUrl.Text);
            if (string.IsNullOrWhiteSpace(name))
            {
                throw new InvalidOperationException("请填写地图源名称。");
            }

            if (string.IsNullOrWhiteSpace(tileUrl))
            {
                throw new InvalidOperationException("请填写 XYZ URL 模板。");
            }

            if (string.IsNullOrWhiteSpace(attribution))
            {
                throw new InvalidOperationException("请填写地图服务归属。");
            }

            var placement = SelectedPlacement();
            var apiKey = placement == ProviderKeyPlacement.None
                ? null
                : NullIfWhiteSpace(_apiKey.Text);
            var keyName = placement is ProviderKeyPlacement.Header
                    or ProviderKeyPlacement.QueryParameter
                ? NullIfWhiteSpace(_keyName.Text)
                : null;
            if (placement != ProviderKeyPlacement.None && apiKey is null)
            {
                throw new InvalidOperationException("当前 Key 放置方式需要填写 Key 值。");
            }

            if (placement == ProviderKeyPlacement.UrlTemplate
                && !tileUrl.Contains("{key}", StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidOperationException("XYZ URL 需要包含 {key} 占位符。");
            }

            if (placement is ProviderKeyPlacement.Header
                    or ProviderKeyPlacement.QueryParameter
                && keyName is null)
            {
                throw new InvalidOperationException("Header 或查询参数方式需要填写 Key 名称。");
            }

            var candidate = new MapProviderDefinition(
                _initialProvider.Id,
                name,
                tileUrl,
                SelectedCoordinateSystem(),
                (int)_maximumZoom.Value,
                attribution,
                geocoderUrl,
                placement,
                keyName,
                apiKey,
                _initialProvider.IsBuiltIn);
            return candidate == _initialProvider
                ? _initialProvider
                : candidate with
                {
                    Id = _initialProvider.IsBuiltIn
                        ? $"custom-{Guid.NewGuid():N}"
                        : _initialProvider.Id,
                    IsBuiltIn = false,
                };
        }

        private ProviderKeyPlacement SelectedPlacement() =>
            _keyPlacement.SelectedItem is PlacementChoice choice
                ? choice.Value
                : ProviderKeyPlacement.None;

        private CoordinateSystem SelectedCoordinateSystem() =>
            _coordinateSystem.SelectedItem is CoordinateChoice choice
                ? choice.Value
                : CoordinateSystem.Wgs84;

        private void UpdateKeyFields()
        {
            var placement = SelectedPlacement();
            _apiKey.Enabled = placement != ProviderKeyPlacement.None;
            _keyName.Enabled = placement is ProviderKeyPlacement.Header
                or ProviderKeyPlacement.QueryParameter;
        }

        private void ShowTestStatus(string message, bool? success)
        {
            _testStatus.Text = message;
            _testStatus.ForeColor = success switch
            {
                true => DesignTokens.PaceTeal,
                false => DesignTokens.HeartBerry,
                _ => DesignTokens.QuietInk,
            };
        }

        private static Button DialogButton(
            string text,
            EventHandler onClick,
            bool emphasized = false)
        {
            var button = CreateButton(
                text,
                onClick,
                emphasized ? ButtonTone.Ink : ButtonTone.Neutral,
                compact: true);
            button.MinimumSize = new DrawingSize(80, DesignTokens.MinimumTargetSize);
            return button;
        }

        private static void SelectChoice<TChoice, TValue>(
            ComboBox comboBox,
            TValue value,
            Func<TChoice, TValue> selector)
            where TChoice : class
        {
            for (var index = 0; index < comboBox.Items.Count; index++)
            {
                if (comboBox.Items[index] is TChoice choice
                    && EqualityComparer<TValue>.Default.Equals(selector(choice), value))
                {
                    comboBox.SelectedIndex = index;
                    return;
                }
            }

            comboBox.SelectedIndex = comboBox.Items.Count > 0 ? 0 : -1;
        }

        private static string? NullIfWhiteSpace(string value) =>
            string.IsNullOrWhiteSpace(value) ? null : value.Trim();

        private sealed record PlacementChoice(string Label, ProviderKeyPlacement Value)
        {
            public override string ToString() => Label;
        }

        private sealed record CoordinateChoice(string Label, CoordinateSystem Value)
        {
            public override string ToString() => Label;
        }
    }
}
