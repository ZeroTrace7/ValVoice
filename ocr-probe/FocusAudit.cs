using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

class Program
{
    [DllImport("user32.dll")]
    static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    static extern int GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

    [DllImport("user32.dll")]
    static extern int GetClassName(IntPtr hWnd, StringBuilder lpClassName, int nMaxCount);

    [DllImport("user32.dll")]
    static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    static void Main()
    {
        Console.WriteLine("Switch to Valorant NOW! Waiting 10 seconds...");
        for(int i = 10; i > 0; i--) {
            Console.WriteLine(i + "...");
            Thread.Sleep(1000);
        }

        IntPtr hwnd = GetForegroundWindow();
        GetWindowThreadProcessId(hwnd, out uint pid);
        
        var proc = Process.GetProcessById((int)pid);
        
        var className = new StringBuilder(256);
        GetClassName(hwnd, className, 256);
        
        var title = new StringBuilder(256);
        GetWindowText(hwnd, title, 256);
        
        GetWindowRect(hwnd, out RECT rect);

        Console.WriteLine("\n=== FOREGROUND WINDOW CAPTURED ===");
        Console.WriteLine($"HWND:       0x{hwnd:X}");
        Console.WriteLine($"PID:        {pid}");
        Console.WriteLine($"Process:    {proc.ProcessName}");
        Console.WriteLine($"Class:      {className}");
        Console.WriteLine($"Title:      {title}");
        Console.WriteLine($"Size:       {rect.Right - rect.Left}x{rect.Bottom - rect.Top}");
    }
}
