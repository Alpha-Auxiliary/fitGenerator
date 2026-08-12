using System.Drawing.Drawing2D;

namespace FitGenerator.Native.UI;

internal static class DesignTokens
{
    internal static readonly Color FieldPaper = ColorTranslator.FromHtml("#F3F6F1");
    internal static readonly Color ScoreboardInk = ColorTranslator.FromHtml("#16221C");
    internal static readonly Color TrackRed = ColorTranslator.FromHtml("#D14E39");
    internal static readonly Color PaceTeal = ColorTranslator.FromHtml("#1D6F78");
    internal static readonly Color HeartBerry = ColorTranslator.FromHtml("#C2385A");
    internal static readonly Color LaneGrayGreen = ColorTranslator.FromHtml("#B8C3B8");

    internal static readonly Color QuietInk = Mix(ScoreboardInk, FieldPaper, 0.625f);
    internal static readonly Color PaperRaised = Mix(FieldPaper, Color.White, 0.48f);
    internal static readonly Color LaneSoft = Mix(LaneGrayGreen, FieldPaper, 0.52f);

    internal const int SidebarWidth = 380;
    internal const int RadiusSmall = 6;
    internal const int RadiusLarge = 10;
    internal const int MinimumTargetSize = 40;

    internal static Font CreateDisplayFont(float size, FontStyle style = FontStyle.Regular) =>
        CreateFirstAvailableFont(
            size,
            style,
            "Bahnschrift SemiCondensed",
            "Bahnschrift",
            "Segoe UI Variable Display",
            "Microsoft YaHei UI");

    internal static Font CreateBodyFont(float size, FontStyle style = FontStyle.Regular) =>
        CreateFirstAvailableFont(
            size,
            style,
            "Segoe UI Variable Text",
            "Microsoft YaHei UI",
            "Segoe UI");

    internal static Font CreateMetricFont(float size, FontStyle style = FontStyle.Regular) =>
        CreateFirstAvailableFont(
            size,
            style,
            "Cascadia Mono",
            "Bahnschrift",
            "Consolas",
            "Microsoft YaHei UI");

    internal static void SetDisplayFont(
        Control control,
        float size,
        FontStyle style = FontStyle.Regular) =>
        SetOwnedFont(control, CreateDisplayFont(size, style));

    internal static void SetBodyFont(
        Control control,
        float size,
        FontStyle style = FontStyle.Regular) =>
        SetOwnedFont(control, CreateBodyFont(size, style));

    internal static void SetMetricFont(
        Control control,
        float size,
        FontStyle style = FontStyle.Regular) =>
        SetOwnedFont(control, CreateMetricFont(size, style));

    internal static void AttachBottomSeparator(Control control, int logicalInset = 0) =>
        AttachHorizontalSeparator(control, logicalInset, atTop: false);

    internal static void AttachTopSeparator(Control control, int logicalInset = 0) =>
        AttachHorizontalSeparator(control, logicalInset, atTop: true);

    private static void AttachHorizontalSeparator(
        Control control,
        int logicalInset,
        bool atTop)
    {
        ArgumentNullException.ThrowIfNull(control);
        var brush = new SolidBrush(LaneGrayGreen);
        PaintEventHandler paintHandler = (_, eventArgs) =>
        {
            var scale = control.DeviceDpi / 96f;
            var inset = Math.Max(0, (int)Math.Round(logicalInset * scale));
            var thickness = Math.Max(1, (int)Math.Round(scale));
            var width = control.ClientSize.Width - inset * 2;
            if (width <= 0 || control.ClientSize.Height <= 0)
            {
                return;
            }

            eventArgs.Graphics.FillRectangle(
                brush,
                inset,
                atTop ? 0 : control.ClientSize.Height - thickness,
                width,
                thickness);
        };
        control.Paint += paintHandler;
        control.Disposed += (_, _) =>
        {
            control.Paint -= paintHandler;
            brush.Dispose();
        };
    }

    internal static void AttachRoundedRegion(Control control, int logicalRadius)
    {
        ArgumentNullException.ThrowIfNull(control);
        EventHandler resizeHandler = (_, _) => ApplyRoundedRegion(control, logicalRadius);
        control.Resize += resizeHandler;
        control.Disposed += (_, _) =>
        {
            control.Resize -= resizeHandler;
            var region = control.Region;
            region?.Dispose();
        };
        ApplyRoundedRegion(control, logicalRadius);
    }

    private static void ApplyRoundedRegion(Control control, int logicalRadius)
    {
        ArgumentNullException.ThrowIfNull(control);
        if (control.Width <= 0 || control.Height <= 0)
        {
            return;
        }

        var scale = control.DeviceDpi / 96f;
        var radius = Math.Max(1, (int)Math.Round(logicalRadius * scale));
        var diameter = Math.Min(radius * 2, Math.Min(control.Width, control.Height));
        var bounds = new Rectangle(0, 0, control.Width, control.Height);
        using var path = new GraphicsPath();
        path.AddArc(bounds.Left, bounds.Top, diameter, diameter, 180, 90);
        path.AddArc(bounds.Right - diameter, bounds.Top, diameter, diameter, 270, 90);
        path.AddArc(
            bounds.Right - diameter,
            bounds.Bottom - diameter,
            diameter,
            diameter,
            0,
            90);
        path.AddArc(bounds.Left, bounds.Bottom - diameter, diameter, diameter, 90, 90);
        path.CloseFigure();

        var previous = control.Region;
        control.Region = new Region(path);
        previous?.Dispose();
    }

    private static Font CreateFirstAvailableFont(
        float size,
        FontStyle style,
        params string[] familyNames)
    {
        foreach (var familyName in familyNames)
        {
            try
            {
                using var family = new FontFamily(familyName);
                if (!family.IsStyleAvailable(style))
                {
                    continue;
                }

                return new Font(family, size, style, GraphicsUnit.Point);
            }
            catch (ArgumentException)
            {
                // Try the next local Windows font in the role's fallback list.
            }
        }

        var fallbackFamily = SystemFonts.MessageBoxFont.FontFamily;
        var fallbackStyle = fallbackFamily.IsStyleAvailable(style)
            ? style
            : FontStyle.Regular;
        return new Font(fallbackFamily, size, fallbackStyle, GraphicsUnit.Point);
    }

    private static void SetOwnedFont(Control control, Font font)
    {
        ArgumentNullException.ThrowIfNull(control);
        ArgumentNullException.ThrowIfNull(font);
        try
        {
            control.Font = font;
        }
        catch
        {
            font.Dispose();
            throw;
        }

        control.Disposed += (_, _) => font.Dispose();
    }

    private static Color Mix(Color foreground, Color background, float amount)
    {
        var weight = Math.Clamp(amount, 0f, 1f);
        return Color.FromArgb(
            255,
            (int)Math.Round(background.R + (foreground.R - background.R) * weight),
            (int)Math.Round(background.G + (foreground.G - background.G) * weight),
            (int)Math.Round(background.B + (foreground.B - background.B) * weight));
    }
}
