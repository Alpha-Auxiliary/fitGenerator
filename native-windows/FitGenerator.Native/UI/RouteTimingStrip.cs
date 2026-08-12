using System.Diagnostics;
using System.Drawing.Drawing2D;
using FitGenerator.Native.Core;

namespace FitGenerator.Native.UI;

internal readonly record struct RouteTimingStripSample(
    ulong DistanceCentimeters,
    ulong ElapsedMilliseconds,
    uint SpeedMillimetersPerSecond,
    byte HeartRateBpm);

internal enum RouteTimingStripPlaceholder
{
    Empty,
    Loading,
    Error,
}

internal sealed class RouteTimingStripFrameChangedEventArgs(
    ActivitySampleDto sample,
    double progress) : EventArgs
{
    internal ActivitySampleDto Sample { get; } = sample;

    internal double Progress { get; } = progress;
}

internal sealed class RouteTimingStrip : Control
{
    private const int PlaybackIntervalMilliseconds = 50;
    private const int PlaybackDurationMilliseconds = 6_000;

    private static readonly string[] MetricLabels = ["距离", "预计时间", "配速", "心率"];
    private static readonly Color[] MetricColors =
    [
        DesignTokens.ScoreboardInk,
        DesignTokens.ScoreboardInk,
        DesignTokens.PaceTeal,
        DesignTokens.HeartBerry,
    ];

    private readonly System.Windows.Forms.Timer _playbackTimer = new()
    {
        Interval = PlaybackIntervalMilliseconds,
    };
    private readonly Stopwatch _playbackClock = new();
    private readonly Font _labelFont = DesignTokens.CreateBodyFont(8.5f, FontStyle.Bold);
    private readonly Font _valueFont = DesignTokens.CreateMetricFont(13.5f, FontStyle.Bold);
    private readonly SolidBrush _markerBrush = new(DesignTokens.TrackRed);
    private readonly string[] _metricValues = ["—.—— km", "--:--", "--'--\" /km", "--- bpm"];
    private Pen? _lanePen;
    private Pen? _progressPen;
    private Pen? _separatorPen;
    private int _paintResourceDpi;
    private bool _resourcesDisposed;
    private double _displayProgress;
    private ActivityModelDto? _playbackModel;
    private IReadOnlyList<ActivitySampleDto> _playbackSamples =
        Array.Empty<ActivitySampleDto>();
    private ActivitySampleDto? _canonicalSample;
    private RouteTimingStripSample? _sample;
    private RouteTimingStripPlaceholder? _placeholder = RouteTimingStripPlaceholder.Empty;

    internal RouteTimingStrip()
    {
        SetStyle(
            ControlStyles.AllPaintingInWmPaint
            | ControlStyles.OptimizedDoubleBuffer
            | ControlStyles.ResizeRedraw
            | ControlStyles.UserPaint,
            true);
        BackColor = DesignTokens.FieldPaper;
        ForeColor = DesignTokens.ScoreboardInk;
        MinimumSize = new Size(0, 96);
        TabStop = false;
        AccessibleRole = AccessibleRole.StaticText;
        AccessibleName = "路线计时带";
        _playbackTimer.Tick += OnPlaybackTick;
        DesignTokens.AttachRoundedRegion(this, DesignTokens.RadiusLarge);
    }

    internal event EventHandler<RouteTimingStripFrameChangedEventArgs>?
        PlaybackFrameChanged;

    internal double Progress => _displayProgress;

    internal RouteTimingStripSample? Sample => _sample;

    internal void SetData(double normalizedProgress, RouteTimingStripSample? sample)
    {
        StopPlayback(clearModel: true);
        _canonicalSample = null;
        _sample = sample;
        _placeholder = sample is null ? RouteTimingStripPlaceholder.Empty : null;
        _displayProgress = double.IsFinite(normalizedProgress)
            ? Math.Clamp(normalizedProgress, 0.0, 1.0)
            : 0.0;
        UpdateMetricValues();
        UpdateAccessibleDescription();
        Invalidate();
    }

    internal void Play(ActivityModelDto model)
    {
        ArgumentNullException.ThrowIfNull(model);

        if (ReferenceEquals(_playbackModel, model))
        {
            if (_canonicalSample is null && _playbackSamples.Count > 0)
            {
                ApplyCanonicalFrame(
                    _playbackSamples[^1],
                    PlaybackEndTime(model, _playbackSamples),
                    progressOverride: 1.0);
            }

            return;
        }

        StopPlayback(clearModel: false);
        _playbackModel = model;
        _playbackSamples = OrderCanonicalSamples(model.Samples);
        _canonicalSample = null;

        if (_playbackSamples.Count == 0)
        {
            SetPlaceholder(RouteTimingStripPlaceholder.Error);
            return;
        }

        _placeholder = null;
        var endTime = PlaybackEndTime(model, _playbackSamples);
        if (_playbackSamples.Count == 1
            || endTime == 0
            || !SystemInformation.UIEffectsEnabled
            || !IsHandleCreated)
        {
            ApplyCanonicalFrame(
                _playbackSamples[^1],
                endTime,
                progressOverride: 1.0);
            return;
        }

        _playbackClock.Restart();
        ApplyCanonicalFrame(
            FindNearestCanonicalSample(_playbackSamples, targetTimeMs: 0),
            endTime);
        _playbackTimer.Start();
    }

    internal void SetPlaceholder(RouteTimingStripPlaceholder placeholder)
    {
        StopPlayback(clearModel: placeholder is not RouteTimingStripPlaceholder.Loading);
        _canonicalSample = null;
        _sample = null;
        _placeholder = placeholder;
        _displayProgress = 0;
        UpdateMetricValues();
        UpdateAccessibleDescription();
        Invalidate();
    }

    protected override void OnHandleDestroyed(EventArgs eventArgs)
    {
        if (!_resourcesDisposed)
        {
            StopPlayback(clearModel: false);
        }

        base.OnHandleDestroyed(eventArgs);
    }

    protected override void OnPaint(PaintEventArgs eventArgs)
    {
        base.OnPaint(eventArgs);
        EnsurePaintResources();
        var graphics = eventArgs.Graphics;
        graphics.SmoothingMode = SmoothingMode.AntiAlias;
        graphics.Clear(DesignTokens.FieldPaper);

        var scale = DeviceDpi / 96f;
        var horizontalPadding = Math.Max(16, (int)Math.Round(22 * scale));
        var laneY = Math.Max(12, (int)Math.Round(17 * scale));
        var laneLeft = horizontalPadding;
        var laneRight = Math.Max(laneLeft + 1, Width - horizontalPadding);
        var progressX = laneLeft
            + (int)Math.Round((laneRight - laneLeft) * _displayProgress);

        graphics.DrawLine(_lanePen!, laneLeft, laneY, laneRight, laneY);
        if (progressX > laneLeft)
        {
            graphics.DrawLine(_progressPen!, laneLeft, laneY, progressX, laneY);
        }

        var markerRadius = Math.Max(4, (int)Math.Round(6 * scale));
        graphics.FillEllipse(
            _markerBrush,
            progressX - markerRadius,
            laneY - markerRadius,
            markerRadius * 2,
            markerRadius * 2);

        var metricsTop = Math.Max(laneY + markerRadius + 5, (int)Math.Round(31 * scale));
        var metricsHeight = Math.Max(1, Height - metricsTop - (int)Math.Round(8 * scale));
        var availableWidth = Math.Max(4, Width - horizontalPadding * 2);
        var metricWidth = availableWidth / 4;
        for (var index = 0; index < MetricLabels.Length; index++)
        {
            var left = horizontalPadding + metricWidth * index;
            var width = index == MetricLabels.Length - 1
                ? Width - horizontalPadding - left
                : metricWidth;
            var labelBounds = new Rectangle(left, metricsTop, width, metricsHeight / 3);
            var valueBounds = new Rectangle(
                left,
                metricsTop + metricsHeight / 3,
                width,
                metricsHeight * 2 / 3);
            TextRenderer.DrawText(
                graphics,
                MetricLabels[index],
                _labelFont,
                labelBounds,
                DesignTokens.QuietInk,
                TextFormatFlags.HorizontalCenter
                | TextFormatFlags.VerticalCenter
                | TextFormatFlags.NoPadding);
            TextRenderer.DrawText(
                graphics,
                _metricValues[index],
                _valueFont,
                valueBounds,
                MetricColors[index],
                TextFormatFlags.HorizontalCenter
                | TextFormatFlags.VerticalCenter
                | TextFormatFlags.EndEllipsis
                | TextFormatFlags.NoPadding);

            if (index < MetricLabels.Length - 1)
            {
                var separatorX = left + width;
                graphics.DrawLine(
                    _separatorPen!,
                    separatorX,
                    metricsTop + 3,
                    separatorX,
                    Height - (int)Math.Round(10 * scale));
            }
        }
    }

    protected override void Dispose(bool disposing)
    {
        var disposeTimer = false;
        if (disposing && !_resourcesDisposed)
        {
            StopPlayback(clearModel: true);
            _playbackTimer.Tick -= OnPlaybackTick;
            PlaybackFrameChanged = null;
            DisposePaintResources();
            _markerBrush.Dispose();
            _labelFont.Dispose();
            _valueFont.Dispose();
            _resourcesDisposed = true;
            disposeTimer = true;
        }

        try
        {
            base.Dispose(disposing);
        }
        finally
        {
            if (disposeTimer)
            {
                _playbackTimer.Dispose();
            }
        }
    }

    private void OnPlaybackTick(object? sender, EventArgs eventArgs)
    {
        if (_playbackModel is not { } model || _playbackSamples.Count == 0)
        {
            StopPlayback(clearModel: false);
            return;
        }

        var endTime = PlaybackEndTime(model, _playbackSamples);
        if (!SystemInformation.UIEffectsEnabled)
        {
            StopPlayback(clearModel: false);
            ApplyCanonicalFrame(
                _playbackSamples[^1],
                endTime,
                progressOverride: 1.0);
            return;
        }

        var playbackProgress = Math.Clamp(
            _playbackClock.Elapsed.TotalMilliseconds / PlaybackDurationMilliseconds,
            0.0,
            1.0);
        if (playbackProgress >= 1.0)
        {
            ApplyCanonicalFrame(
                _playbackSamples[^1],
                endTime,
                progressOverride: 1.0);
            StopPlayback(clearModel: false);
            return;
        }

        var targetTime = (ulong)Math.Round(endTime * playbackProgress);
        ApplyCanonicalFrame(
            FindNearestCanonicalSample(_playbackSamples, targetTime),
            endTime);
    }

    private void ApplyCanonicalFrame(
        ActivitySampleDto sample,
        ulong endTime,
        double? progressOverride = null)
    {
        var progress = progressOverride ?? (endTime == 0
            ? 1.0
            : Math.Clamp(sample.TimeMs / (double)endTime, 0.0, 1.0));
        if (ReferenceEquals(_canonicalSample, sample)
            && Math.Abs(_displayProgress - progress) < double.Epsilon)
        {
            return;
        }

        _canonicalSample = sample;
        _sample = new RouteTimingStripSample(
            sample.DistanceCm,
            sample.TimeMs,
            sample.SpeedMmPerSec,
            sample.HeartRateBpm);
        _placeholder = null;
        _displayProgress = progress;
        UpdateMetricValues();
        UpdateAccessibleDescription();
        Invalidate();
        PlaybackFrameChanged?.Invoke(
            this,
            new RouteTimingStripFrameChangedEventArgs(sample, progress));
    }

    private void StopPlayback(bool clearModel)
    {
        _playbackTimer.Stop();
        _playbackClock.Reset();
        if (clearModel)
        {
            _playbackModel = null;
            _playbackSamples = Array.Empty<ActivitySampleDto>();
        }
    }

    private static ulong PlaybackEndTime(
        ActivityModelDto model,
        IReadOnlyList<ActivitySampleDto> samples) =>
        samples.Count == 0
            ? model.TotalDurationMs
            : Math.Max(model.TotalDurationMs, samples[^1].TimeMs);

    private static IReadOnlyList<ActivitySampleDto> OrderCanonicalSamples(
        IReadOnlyList<ActivitySampleDto> samples)
    {
        for (var index = 1; index < samples.Count; index++)
        {
            if (samples[index - 1].TimeMs > samples[index].TimeMs)
            {
                return samples.OrderBy(sample => sample.TimeMs).ToArray();
            }
        }

        return samples;
    }

    internal static ActivitySampleDto FindNearestCanonicalSample(
        IReadOnlyList<ActivitySampleDto> samples,
        ulong targetTimeMs)
    {
        ArgumentNullException.ThrowIfNull(samples);
        if (samples.Count == 0)
        {
            throw new ArgumentException(
                "At least one canonical sample is required.",
                nameof(samples));
        }

        if (targetTimeMs >= samples[^1].TimeMs)
        {
            return samples[^1];
        }

        var lower = 0;
        var upper = samples.Count;
        while (lower < upper)
        {
            var middle = lower + (upper - lower) / 2;
            if (samples[middle].TimeMs <= targetTimeMs)
            {
                lower = middle + 1;
            }
            else
            {
                upper = middle;
            }
        }

        if (lower == 0)
        {
            return samples[0];
        }

        if (lower == samples.Count)
        {
            return samples[^1];
        }

        var later = samples[lower];
        var earlier = samples[lower - 1];
        var earlierDistance = targetTimeMs - earlier.TimeMs;
        var laterDistance = later.TimeMs - targetTimeMs;
        return earlierDistance <= laterDistance ? earlier : later;
    }

    private void EnsurePaintResources()
    {
        var dpi = Math.Max(96, DeviceDpi);
        if (_paintResourceDpi == dpi
            && _lanePen is not null
            && _progressPen is not null
            && _separatorPen is not null)
        {
            return;
        }

        DisposePaintResources();
        var scale = dpi / 96f;
        _lanePen = new Pen(DesignTokens.LaneGrayGreen, Math.Max(1f, 2f * scale));
        _progressPen = new Pen(DesignTokens.TrackRed, Math.Max(2f, 4f * scale))
        {
            StartCap = LineCap.Round,
            EndCap = LineCap.Round,
        };
        _separatorPen = new Pen(DesignTokens.LaneSoft, Math.Max(1f, scale));
        _paintResourceDpi = dpi;
    }

    private void DisposePaintResources()
    {
        _lanePen?.Dispose();
        _progressPen?.Dispose();
        _separatorPen?.Dispose();
        _lanePen = null;
        _progressPen = null;
        _separatorPen = null;
        _paintResourceDpi = 0;
    }

    private void UpdateMetricValues()
    {
        if (_sample is not { } sample)
        {
            _metricValues[0] = "—.—— km";
            _metricValues[1] = "--:--";
            _metricValues[2] = "--'--\" /km";
            _metricValues[3] = "--- bpm";
            return;
        }

        _metricValues[0] = $"{sample.DistanceCentimeters / 100_000.0:0.00} km";
        _metricValues[1] = FormatDuration(sample.ElapsedMilliseconds);
        _metricValues[2] = FormatPace(sample.SpeedMillimetersPerSecond);
        _metricValues[3] = sample.HeartRateBpm == 0
            ? "--- bpm"
            : $"{sample.HeartRateBpm} bpm";
    }

    private void UpdateAccessibleDescription()
    {
        AccessibleDescription = _sample is not null
            ? $"进度 {_displayProgress:P0}，距离 {_metricValues[0]}，预计时间 {_metricValues[1]}，"
                + $"配速 {_metricValues[2]}，心率 {_metricValues[3]}。"
            : _placeholder switch
            {
                RouteTimingStripPlaceholder.Loading =>
                    "正在生成预览。完成后将显示距离、预计时间、配速和心率。",
                RouteTimingStripPlaceholder.Error =>
                    "预览数据不可用。请查看当前状态提示，修正后重新生成预览。",
                _ => "尚无预览数据。请先绘制路线，再生成预览。",
            };
    }

    private static string FormatDuration(ulong milliseconds)
    {
        var totalSeconds = milliseconds / 1_000;
        var hours = totalSeconds / 3_600;
        var minutes = totalSeconds % 3_600 / 60;
        var seconds = totalSeconds % 60;
        return hours > 0
            ? $"{hours}:{minutes:00}:{seconds:00}"
            : $"{minutes:00}:{seconds:00}";
    }

    private static string FormatPace(uint speedMillimetersPerSecond)
    {
        if (speedMillimetersPerSecond == 0)
        {
            return "--'--\" /km";
        }

        var paceSeconds = (int)Math.Round(1_000_000.0 / speedMillimetersPerSecond);
        var minutes = paceSeconds / 60;
        var seconds = paceSeconds % 60;
        return $"{minutes}'{seconds:00}\" /km";
    }
}
