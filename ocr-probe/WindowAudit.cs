using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

/// <summary>
/// WindowAudit — Phase 0C diagnostic utility.
/// Enumerates all top-level windows and collects the data required to
/// identify the actual Valorant render HWND.
///
/// Output: console + C:\Temp\window_audit.txt
/// Run with Valorant open in Borderless Windowed mode.
/// </summary>
internal class WindowAudit
{
    // ── P/Invoke ─────────────────────────────────────────────────────────

    private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr hwnd);

    [DllImport("user32.dll")]
    private static extern int GetWindowText(IntPtr hwnd, StringBuilder sb, int maxCount);

    [DllImport("user32.dll")]
    private static extern int GetClassName(IntPtr hwnd, StringBuilder sb, int maxCount);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);

    [DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr hwnd, out RECT lpRect);

    [DllImport("user32.dll")]
    private static extern bool GetClientRect(IntPtr hwnd, out RECT lpRect);

    [DllImport("user32.dll")]
    private static extern int GetWindowLong(IntPtr hwnd, int nIndex);

    [DllImport("user32.dll")]
    private static extern IntPtr GetWindow(IntPtr hwnd, uint uCmd);

    [DllImport("user32.dll")]
    private static extern IntPtr GetAncestor(IntPtr hwnd, uint gaFlags);

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT
    {
        public int Left, Top, Right, Bottom;
        public int Width  => Right - Left;
        public int Height => Bottom - Top;
    }

    // GetWindowLong indices
    private const int GWL_STYLE   = -16;
    private const int GWL_EXSTYLE = -20;

    // GetWindow cmd
    private const uint GW_OWNER = 4;

    // GetAncestor flag
    private const uint GA_PARENT = 1;

    // Style flags
    private const uint WS_VISIBLE       = 0x10000000;
    private const uint WS_POPUP         = 0x80000000;
    private const uint WS_CHILD         = 0x40000000;
    private const uint WS_EX_TOOLWINDOW = 0x00000080;
    private const uint WS_EX_TOPMOST    = 0x00000008;
    private const uint WS_EX_LAYERED    = 0x00080000;

    // ── Row ──────────────────────────────────────────────────────────────

    private record WindowRow(
        string  Hwnd,
        uint    Pid,
        string  ProcessName,
        string  Title,
        string  ClassName,
        bool    Visible,
        string  Style,
        string  ExStyle,
        int     Left,
        int     Top,
        int     Width,
        int     Height,
        int     ClientWidth,
        int     ClientHeight,
        string  OwnerHwnd,
        string  ParentHwnd,
        bool    IsTarget         // belongs to Valorant or Riot
    );

    // ── Main ─────────────────────────────────────────────────────────────

    static void Main()
    {
        Console.OutputEncoding = Encoding.UTF8;

        Console.WriteLine("╔══════════════════════════════════════════════════════════╗");
        Console.WriteLine("║  WindowAudit — Phase 0C HWND Diagnostic                ║");
        Console.WriteLine("╚══════════════════════════════════════════════════════════╝");
        Console.WriteLine();

        var rows = new List<WindowRow>();

        EnumWindows((hwnd, _) =>
        {
            rows.Add(BuildRow(hwnd));
            return true; // continue enumeration
        }, IntPtr.Zero);

        Console.WriteLine($"Total top-level windows enumerated: {rows.Count}");
        Console.WriteLine();

        // ── Print: Valorant / Riot rows first ──────────────────────────

        var targetRows = rows.Where(r => r.IsTarget).OrderBy(r => r.Pid).ThenBy(r => r.Hwnd).ToList();
        var otherRows  = rows.Where(r => !r.IsTarget).OrderBy(r => r.Pid).ThenBy(r => r.Hwnd).ToList();

        var sb = new StringBuilder();

        AppendSection(sb, "═══ VALORANT / RIOT WINDOWS (filtered) ═══", targetRows);
        AppendSection(sb, "═══ ALL OTHER WINDOWS ═══", otherRows);
        AppendCriteriaAnalysis(sb, targetRows);

        string output = sb.ToString();
        Console.Write(output);

        // ── Save to file ───────────────────────────────────────────────

        Directory.CreateDirectory(@"C:\Temp");
        string path = @"C:\Temp\window_audit.txt";
        File.WriteAllText(path, output, Encoding.UTF8);
        Console.WriteLine();
        Console.WriteLine($"[SAVED] Full output written to: {path}");
    }

    // ── Build row ────────────────────────────────────────────────────────

    private static WindowRow BuildRow(IntPtr hwnd)
    {
        // Title
        var titleSb = new StringBuilder(256);
        GetWindowText(hwnd, titleSb, 256);
        string title = titleSb.ToString();

        // Class name
        var classSb = new StringBuilder(256);
        GetClassName(hwnd, classSb, 256);
        string className = classSb.ToString();

        // PID
        GetWindowThreadProcessId(hwnd, out uint pid);

        // Process name
        string procName = "(unknown)";
        try { procName = Process.GetProcessById((int)pid).ProcessName; }
        catch { }

        // Rects
        GetWindowRect(hwnd, out RECT winRect);
        GetClientRect(hwnd, out RECT clientRect);

        // Visibility
        bool visible = IsWindowVisible(hwnd);

        // Styles
        uint style   = (uint)GetWindowLong(hwnd, GWL_STYLE);
        uint exStyle = (uint)GetWindowLong(hwnd, GWL_EXSTYLE);

        // Owner / parent
        IntPtr owner  = GetWindow(hwnd, GW_OWNER);
        IntPtr parent = GetAncestor(hwnd, GA_PARENT);

        bool isTarget = procName.Equals("VALORANT-Win64-Shipping", StringComparison.OrdinalIgnoreCase)
                     || procName.Equals("RiotClientServices",       StringComparison.OrdinalIgnoreCase)
                     || procName.Equals("vgtray",                   StringComparison.OrdinalIgnoreCase)
                     || procName.Equals("vgc",                      StringComparison.OrdinalIgnoreCase)
                     || procName.Equals("RiotClientUx",             StringComparison.OrdinalIgnoreCase);

        return new WindowRow(
            Hwnd:        $"0x{hwnd:X}",
            Pid:         pid,
            ProcessName: procName,
            Title:       title,
            ClassName:   className,
            Visible:     visible,
            Style:       $"0x{style:X8}",
            ExStyle:     $"0x{exStyle:X8}",
            Left:        winRect.Left,
            Top:         winRect.Top,
            Width:       winRect.Width,
            Height:      winRect.Height,
            ClientWidth: clientRect.Width,
            ClientHeight:clientRect.Height,
            OwnerHwnd:   owner  == IntPtr.Zero ? "0" : $"0x{owner:X}",
            ParentHwnd:  parent == IntPtr.Zero ? "0" : $"0x{parent:X}",
            IsTarget:    isTarget
        );
    }

    // ── Print section ────────────────────────────────────────────────────

    private static void AppendSection(StringBuilder sb, string header, List<WindowRow> rows)
    {
        sb.AppendLine();
        sb.AppendLine(header);
        sb.AppendLine(new string('─', 120));

        if (rows.Count == 0)
        {
            sb.AppendLine("  (none)");
            return;
        }

        // Column headers
        sb.AppendLine(
            $"{"HWND",-12} {"PID",-7} {"Process",-30} {"Vis",-4} {"WxH",-14} {"ClientWxH",-14} " +
            $"{"Class",-25} {"Owner",-12} {"Parent",-12} {"Style",-12} {"ExStyle",-12} Title"
        );
        sb.AppendLine(new string('─', 200));

        foreach (var r in rows)
        {
            string dim       = $"{r.Width}x{r.Height}";
            string clientDim = $"{r.ClientWidth}x{r.ClientHeight}";

            sb.AppendLine(
                $"{r.Hwnd,-12} {r.Pid,-7} {Trunc(r.ProcessName, 30),-30} {(r.Visible ? "Y" : "N"),-4} " +
                $"{dim,-14} {clientDim,-14} {Trunc(r.ClassName, 25),-25} {r.OwnerHwnd,-12} {r.ParentHwnd,-12} " +
                $"{r.Style,-12} {r.ExStyle,-12} {Trunc(r.Title, 60)}"
            );
        }
    }

    // ── Criteria analysis ─────────────────────────────────────────────────

    private static void AppendCriteriaAnalysis(StringBuilder sb, List<WindowRow> targetRows)
    {
        sb.AppendLine();
        sb.AppendLine("═══ RENDER HWND CANDIDATE ANALYSIS ═══");
        sb.AppendLine(new string('─', 120));
        sb.AppendLine();
        sb.AppendLine("Applying identification criteria to Valorant/Riot windows:");
        sb.AppendLine("  C1: ProcessName == VALORANT-Win64-Shipping");
        sb.AppendLine("  C2: ClassName   == UnrealWindow (or similar — inspect actual value)");
        sb.AppendLine("  C3: Width >= 1024 AND Height >= 768");
        sb.AppendLine("  C4: Visible == true");
        sb.AppendLine("  C5: OwnerHwnd == 0 (unowned top-level)");
        sb.AppendLine("  C6: ExStyle does NOT have WS_EX_TOOLWINDOW (0x80)");
        sb.AppendLine();

        var valorantWindows = targetRows
            .Where(r => r.ProcessName.Equals("VALORANT-Win64-Shipping", StringComparison.OrdinalIgnoreCase))
            .ToList();

        if (valorantWindows.Count == 0)
        {
            sb.AppendLine("  !! NO VALORANT-Win64-Shipping windows found by EnumWindows.");
            sb.AppendLine("  !! This means either:");
            sb.AppendLine("  !!   (a) Valorant is not running, OR");
            sb.AppendLine("  !!   (b) Vanguard is hiding the window from EnumWindows.");
            sb.AppendLine("  !! GATE D1: FAIL — cannot proceed.");
            return;
        }

        sb.AppendLine($"  Found {valorantWindows.Count} window(s) belonging to VALORANT-Win64-Shipping:");
        sb.AppendLine();

        foreach (var r in valorantWindows)
        {
            bool c1 = true; // already filtered
            bool c2 = r.ClassName.Equals("UnrealWindow", StringComparison.OrdinalIgnoreCase);
            bool c3 = r.Width >= 1024 && r.Height >= 768;
            bool c4 = r.Visible;
            bool c5 = r.OwnerHwnd == "0";
            uint exStyle = Convert.ToUInt32(r.ExStyle, 16);
            bool c6 = (exStyle & WS_EX_TOOLWINDOW) == 0;

            bool allPass = c1 && c2 && c3 && c4 && c5 && c6;

            sb.AppendLine($"  HWND={r.Hwnd}  Size={r.Width}x{r.Height}  Class=\"{r.ClassName}\"  Title=\"{Trunc(r.Title, 40)}\"");
            sb.AppendLine($"    C1 (process match):       {Pass(c1)}");
            sb.AppendLine($"    C2 (UnrealWindow class):  {Pass(c2)}  [actual class: \"{r.ClassName}\"]");
            sb.AppendLine($"    C3 (size >= 1024x768):    {Pass(c3)}  [{r.Width}x{r.Height}]");
            sb.AppendLine($"    C4 (visible):             {Pass(c4)}");
            sb.AppendLine($"    C5 (no owner):            {Pass(c5)}  [owner={r.OwnerHwnd}]");
            sb.AppendLine($"    C6 (no TOOLWINDOW exsty): {Pass(c6)}  [exstyle={r.ExStyle}]");
            sb.AppendLine($"    ── CANDIDATE: {(allPass ? "YES ✓  << USE THIS HWND" : "NO  ✗")}");

            // Extra diagnostics
            uint style = Convert.ToUInt32(r.Style, 16);
            bool isPopup = (style & WS_POPUP)  != 0;
            bool isChild = (style & WS_CHILD)  != 0;
            bool isTopmost = (exStyle & WS_EX_TOPMOST) != 0;
            bool isLayered = (exStyle & WS_EX_LAYERED) != 0;

            sb.AppendLine($"    [extra] WS_POPUP={isPopup}  WS_CHILD={isChild}  WS_EX_TOPMOST={isTopmost}  WS_EX_LAYERED={isLayered}");
            sb.AppendLine($"    [extra] ClientSize={r.ClientWidth}x{r.ClientHeight}  Parent={r.ParentHwnd}");
            sb.AppendLine();
        }

        // Summary
        var candidates = valorantWindows.Where(r =>
        {
            bool c2 = r.ClassName.Equals("UnrealWindow", StringComparison.OrdinalIgnoreCase);
            bool c3 = r.Width >= 1024 && r.Height >= 768;
            bool c4 = r.Visible;
            bool c5 = r.OwnerHwnd == "0";
            uint exStyle = Convert.ToUInt32(r.ExStyle, 16);
            bool c6 = (exStyle & WS_EX_TOOLWINDOW) == 0;
            return c2 && c3 && c4 && c5 && c6;
        }).ToList();

        sb.AppendLine("─────────────────────────────────────────────────────────────────────────────────────────");

        if (candidates.Count == 1)
        {
            sb.AppendLine($"  GATE D1: PASS");
            sb.AppendLine($"  GATE D2: PASS — exactly one candidate: {candidates[0].Hwnd}");
            sb.AppendLine();
            sb.AppendLine($"  >> Next action: update OcrProbe to use HWND {candidates[0].Hwnd}");
            sb.AppendLine($"     and use EnumWindows + ClassName==\"{candidates[0].ClassName}\" for permanent selection.");
        }
        else if (candidates.Count == 0)
        {
            // Check if C2 is the only failing criterion — give adjusted guidance
            var nearMisses = valorantWindows.Where(r =>
            {
                bool c3 = r.Width >= 1024 && r.Height >= 768;
                bool c4 = r.Visible;
                bool c5 = r.OwnerHwnd == "0";
                uint exStyle = Convert.ToUInt32(r.ExStyle, 16);
                bool c6 = (exStyle & WS_EX_TOOLWINDOW) == 0;
                return c3 && c4 && c5 && c6; // passes everything except class name
            }).ToList();

            if (nearMisses.Count == 1)
            {
                sb.AppendLine($"  GATE D1: PASS");
                sb.AppendLine($"  GATE D2: NEAR-PASS — one window passes C3-C6 but class is NOT 'UnrealWindow'.");
                sb.AppendLine($"    Actual class: \"{nearMisses[0].ClassName}\"");
                sb.AppendLine($"    HWND: {nearMisses[0].Hwnd}  Size: {nearMisses[0].Width}x{nearMisses[0].Height}");
                sb.AppendLine();
                sb.AppendLine($"  >> ACTION: Update C2 criterion to ClassName==\"{nearMisses[0].ClassName}\".");
                sb.AppendLine($"     This window is the most likely render candidate. Test with OcrProbe.");
            }
            else
            {
                sb.AppendLine($"  GATE D1: PASS ({valorantWindows.Count} Valorant windows found)");
                sb.AppendLine($"  GATE D2: FAIL — no window passes all criteria.");
                sb.AppendLine($"  >> Inspect the table above. Check if class name differs from 'UnrealWindow'.");
                sb.AppendLine($"     Look for the largest window belonging to VALORANT-Win64-Shipping.");
            }
        }
        else
        {
            sb.AppendLine($"  GATE D1: PASS");
            sb.AppendLine($"  GATE D2: AMBIGUOUS — {candidates.Count} candidates found:");
            foreach (var c in candidates)
                sb.AppendLine($"    {c.Hwnd}  {c.Width}x{c.Height}  Class=\"{c.ClassName}\"");
            sb.AppendLine();
            sb.AppendLine("  >> Prefer the largest window by ClientWidth × ClientHeight.");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static string Trunc(string s, int max) =>
        s.Length <= max ? s : s[..max];

    private static string Pass(bool b) => b ? "PASS" : "FAIL";
}
