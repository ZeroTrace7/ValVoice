using System;
using System.Diagnostics;
using System.Text;

namespace ValVoiceOCR;

public class WindowDetector : IWindowDetector
{
    public IntPtr FindWindow()
    {
        IntPtr foundHwnd = IntPtr.Zero;

        Interop.EnumWindows((hwnd, lParam) =>
        {
            if (!Interop.IsWindowVisible(hwnd)) return true;

            Interop.GetWindowThreadProcessId(hwnd, out uint pid);
            if (pid == 0) return true;

            StringBuilder tempSb = new StringBuilder(256);
            Interop.GetClassName(hwnd, tempSb, tempSb.Capacity);


            try
            {
                using var process = Process.GetProcessById((int)pid);
                if (process.ProcessName != "VALORANT-Win64-Shipping") return true;
            }
            catch (Exception ex)
            {
                DiagnosticLogger.LogError($"[WindowDetector] PID lookup failed.\nPID={pid}\nHWND=0x{hwnd:X}\nType={ex.GetType().Name}\nMessage={ex.Message}");
                return true; 
            }

            StringBuilder sb = new StringBuilder(256);
            Interop.GetClassName(hwnd, sb, sb.Capacity);
            if (sb.ToString() != "VALORANTUnrealWindow") return true;
            
            DiagnosticLogger.Log($"[WindowDetector] Matched VALORANTUnrealWindow HWND=0x{hwnd:X}");

            long exStyle = Interop.GetWindowLong(hwnd, Interop.GWL_EXSTYLE);
            if ((exStyle & Interop.WS_EX_TOOLWINDOW) != 0) return true;

            IntPtr owner = Interop.GetWindow(hwnd, Interop.GW_OWNER);
            if (owner != IntPtr.Zero) return true;

            Interop.DwmGetWindowAttribute(hwnd, Interop.DWMWA_EXTENDED_FRAME_BOUNDS, out Interop.RECT rect, System.Runtime.InteropServices.Marshal.SizeOf(typeof(Interop.RECT)));
            if (rect.Width > 0 && rect.Height > 0)
            {
                DiagnosticLogger.Log($"[WindowDetector] foundHwnd assigned: HWND=0x{hwnd:X}");
                foundHwnd = hwnd;
                return false; // Stop enumeration
            }

            return true;
        }, IntPtr.Zero);

        return foundHwnd;
    }
}
