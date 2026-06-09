using System.Diagnostics;
using System.Runtime.InteropServices;
using Windows.Graphics;
using Windows.Graphics.Capture;
using Windows.Graphics.DirectX;
using Windows.Graphics.DirectX.Direct3D11;
using Windows.Graphics.Imaging;
using Windows.Media.Ocr;
using Windows.Storage.Streams;
using WinRT;

namespace OcrProbe;

/// <summary>
/// OcrProbe — Phase 0C Feasibility Prototype
///
/// Purpose: Verify that Windows.Graphics.Capture works with Valorant under Vanguard
///          and that Windows.Media.Ocr can read the chat area.
///
/// Steps:
///   1. Find Valorant HWND
///   2. Create WGC capture via IGraphicsCaptureItemInterop::CreateForWindow(HWND)
///   3. Capture one frame
///   4. Save full frame + cropped chat region as PNGs
///   5. Run OCR on crop
///   6. Print raw OCR output
///   7. Print gate criteria pass/fail
///
/// Gate criteria (all must pass):
///   0C.1  No HRESULT error from WGC on Vanguard-protected Valorant
///   0C.2  PNG crop is non-empty and shows visible chat area
///   0C.3  OCR output contains at least one recognizable ASCII word
///   0C.4  Runs on Windows 10 build 19041+ without API-not-found exceptions
///   0C.5  Capture while moving in Range with chat open (manual re-run)
///
/// Usage:
///   dotnet run
///   dotnet run -- --crop 0,70,30,25     (custom crop: x%, y%, w%, h%)
///   dotnet run -- --output C:\MyDir     (custom output directory)
/// </summary>
internal class Program
{
    // Win32 imports
    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsWindowVisible(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern int GetClassName(IntPtr hwnd, System.Text.StringBuilder sb, int maxCount);

    [DllImport("dwmapi.dll")]
    private static extern int DwmGetWindowAttribute(IntPtr hwnd, int dwAttribute, out RECT pvAttribute, int cbAttribute);

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT
    {
        public int Left, Top, Right, Bottom;
        public int Width => Right - Left;
        public int Height => Bottom - Top;
    }

    // D3D11 device creation
    [DllImport("d3d11.dll", EntryPoint = "D3D11CreateDevice", SetLastError = true)]
    private static extern int D3D11CreateDevice(
        IntPtr pAdapter, int DriverType, IntPtr Software, uint Flags,
        IntPtr pFeatureLevels, uint FeatureLevels, uint SDKVersion,
        out IntPtr ppDevice, out int pFeatureLevel, out IntPtr ppImmediateContext);

    // WinRT Direct3D11 interop
    [DllImport("d3d11.dll", EntryPoint = "CreateDirect3D11DeviceFromDXGIDevice")]
    private static extern int CreateDirect3D11DeviceFromDXGIDevice(
        IntPtr dxgiDevice, out IntPtr graphicsDevice);

    // Default crop: bottom-left chat region (x%, y%, w%, h%)
    private static double cropX = 0.0;
    private static double cropY = 0.70;
    private static double cropW = 0.30;
    private static double cropH = 0.25;
    private static string outputDir = @"C:\Temp";

    static async Task<int> Main(string[] args)
    {
        Console.WriteLine("╔══════════════════════════════════════════════════╗");
        Console.WriteLine("║  OcrProbe — Phase 0C.5 Dynamic Validation       ║");
        Console.WriteLine("║  ValVoice OCR Migration Gate                    ║");
        Console.WriteLine("╚══════════════════════════════════════════════════╝\n");

        ParseArgs(args);

        var osVer = Environment.OSVersion.Version;
        if (osVer.Build < 19041)
        {
            Console.WriteLine("[FAIL] Windows build < 19041.");
            return 1;
        }

        IntPtr hwnd = FindValorantWindow();
        if (hwnd == IntPtr.Zero)
        {
            Console.WriteLine("[FAIL] Could not find VALORANTUnrealWindow. Start the game first.");
            return 1;
        }
        Console.WriteLine($"[INFO] Target HWND: 0x{hwnd:X}");

        IDirect3DDevice? d3dDevice = CreateD3D11Device();
        if (d3dDevice == null) return 1;

        GraphicsCaptureItem captureItem = CaptureHelper.CreateItemForWindow(hwnd);
        var ocrEngine = OcrEngine.TryCreateFromLanguage(new Windows.Globalization.Language("en-US"));
        if (ocrEngine == null)
        {
            Console.WriteLine("[FAIL] OCR engine creation failed. Is en-US language pack installed?");
            return 1;
        }

        int iterations = 40;
        int successfulCaptures = 0;
        int failedCaptures = 0;
        int ocrSuccesses = 0;
        int ocrFailures = 0;
        long peakMemory = 0;
        double totalCaptureTime = 0;
        double totalOcrTime = 0;

        Console.WriteLine($"[INFO] Starting {iterations} continuous captures (Gate 0C.5)...");
        var sw = Stopwatch.StartNew();

        for (int i = 1; i <= iterations; i++)
        {
            long memMB = Process.GetCurrentProcess().WorkingSet64 / 1024 / 1024;
            peakMemory = Math.Max(peakMemory, memMB);

            var iterSw = Stopwatch.StartNew();
            SoftwareBitmap? fullBitmap = null;
            try
            {
                fullBitmap = await CaptureOneFrame(captureItem, d3dDevice, captureItem.Size);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[{i:D2}/{iterations}] FAIL: {ex.Message} | Mem: {memMB}MB");
            }
            
            iterSw.Stop();
            double capTime = iterSw.Elapsed.TotalMilliseconds;
            totalCaptureTime += capTime;

            if (fullBitmap == null)
            {
                failedCaptures++;
                if (i < iterations) await Task.Delay(500);
                continue;
            }
            successfulCaptures++;

            int cx = (int)(fullBitmap.PixelWidth * cropX);
            int cy = (int)(fullBitmap.PixelHeight * cropY);
            int cw = (int)(fullBitmap.PixelWidth * cropW);
            int ch = (int)(fullBitmap.PixelHeight * cropH);
            cw = Math.Min(cw, fullBitmap.PixelWidth - cx);
            ch = Math.Min(ch, fullBitmap.PixelHeight - cy);

            iterSw.Restart();
            string preview = "";
            try
            {
                using var croppedBitmap = CropBitmap(fullBitmap, cx, cy, cw, ch);
                using var ocrBitmap = SoftwareBitmap.Convert(croppedBitmap, BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied);
                var result = await ocrEngine.RecognizeAsync(ocrBitmap);
                
                if (string.IsNullOrWhiteSpace(result.Text))
                {
                    ocrFailures++;
                    preview = "(empty)";
                }
                else
                {
                    ocrSuccesses++;
                    preview = result.Text.Replace("\n", " ").Replace("\r", "");
                    if (preview.Length > 50) preview = preview.Substring(0, 47) + "...";
                }
            }
            catch (Exception ex)
            {
                ocrFailures++;
                preview = $"(ocr err: {ex.Message})";
            }
            
            iterSw.Stop();
            double ocrTime = iterSw.Elapsed.TotalMilliseconds;
            totalOcrTime += ocrTime;

            Console.WriteLine($"[{i:D2}/{iterations}] Mem: {memMB}MB | Cap: {capTime:F0}ms | OCR: {ocrTime:F0}ms | Size: {fullBitmap.PixelWidth}x{fullBitmap.PixelHeight} | {preview}");

            fullBitmap.Dispose();
            if (i < iterations) await Task.Delay(500);
        }
        sw.Stop();

        Console.WriteLine("\n╔══════════════════════════════════════════════════╗");
        Console.WriteLine("║  GATE 0C.5 SUMMARY                             ║");
        Console.WriteLine("╠══════════════════════════════════════════════════╣");
        Console.WriteLine($"║  Runtime Duration:    {sw.Elapsed.TotalSeconds:F1}s");
        Console.WriteLine($"║  Peak Memory:         {peakMemory} MB");
        Console.WriteLine($"║  Average Cap Time:    {totalCaptureTime / iterations:F1} ms");
        Console.WriteLine($"║  Average OCR Time:    {totalOcrTime / iterations:F1} ms");
        Console.WriteLine($"║  Capture Successes:   {successfulCaptures}/{iterations}");
        Console.WriteLine($"║  Capture Failures:    {failedCaptures}/{iterations}");
        Console.WriteLine($"║  OCR Successes:       {ocrSuccesses}/{iterations}");
        Console.WriteLine($"║  OCR Failures:        {ocrFailures}/{iterations}");
        Console.WriteLine("╚══════════════════════════════════════════════════╝");

        d3dDevice?.Dispose();
        return failedCaptures == 0 ? 0 : 1;
    }

    private static IntPtr FindValorantWindow()
    {
        var procs = Process.GetProcessesByName("VALORANT-Win64-Shipping");
        foreach (var proc in procs)
        {
            IntPtr hwnd = proc.MainWindowHandle;
            if (hwnd != IntPtr.Zero && IsWindowVisible(hwnd))
            {
                GetWindowRect(hwnd, out RECT rect);
                var classSb = new System.Text.StringBuilder(256);
                GetClassName(hwnd, classSb, 256);
                if (classSb.ToString() == "VALORANTUnrealWindow" && rect.Width > 0 && rect.Height > 0)
                {
                    return hwnd;
                }
            }
        }
        return IntPtr.Zero;
    }

    // ── D3D11 device creation ────────────────────────────────────────────

    private static IDirect3DDevice CreateD3D11Device()
    {
        // Create D3D11 device with hardware driver
        int hr = D3D11CreateDevice(
            IntPtr.Zero,           // Default adapter
            1,                     // D3D_DRIVER_TYPE_HARDWARE
            IntPtr.Zero,           // No software module
            0x20,                  // D3D11_CREATE_DEVICE_BGRA_SUPPORT
            IntPtr.Zero,           // Default feature levels
            0,
            7,                     // D3D11_SDK_VERSION
            out IntPtr d3dDevice,
            out _,
            out _);

        if (hr != 0)
            throw new COMException($"D3D11CreateDevice failed", hr);

        // Get IDXGIDevice from ID3D11Device
        var dxgiGuid = new Guid("54ec77fa-1377-44e6-8c32-88fd5f44c84c"); // IDXGIDevice
        Marshal.QueryInterface(d3dDevice, ref dxgiGuid, out IntPtr dxgiDevice);

        // Create WinRT IDirect3DDevice from IDXGIDevice
        hr = CreateDirect3D11DeviceFromDXGIDevice(dxgiDevice, out IntPtr inspectable);
        if (hr != 0)
            throw new COMException($"CreateDirect3D11DeviceFromDXGIDevice failed", hr);

        var device = WinRT.MarshalInterface<IDirect3DDevice>.FromAbi(inspectable);

        // Release COM refs
        Marshal.Release(inspectable);
        Marshal.Release(dxgiDevice);
        Marshal.Release(d3dDevice);

        return device;
    }

    // ── Frame capture ────────────────────────────────────────────────────

    private static async Task<SoftwareBitmap?> CaptureOneFrame(
        GraphicsCaptureItem item, IDirect3DDevice device, SizeInt32 size)
    {
        var pool = Direct3D11CaptureFramePool.CreateFreeThreaded(
            device,
            DirectXPixelFormat.B8G8R8A8UIntNormalized,
            1,    // frame count
            size);

        var session = pool.CreateCaptureSession(item);

        // IsBorderRequired: not available in 19041 TFM projection.
        // Non-blocking cosmetic property — yellow border doesn't affect OCR.
        // Production sidecar will version-gate this with reflection.
        // Prototype skips it entirely.
        Console.WriteLine("[INFO] IsBorderRequired: skipped (prototype, non-blocking)");

        var tcs = new TaskCompletionSource<Direct3D11CaptureFrame?>();

        pool.FrameArrived += (sender, _) =>
        {
            var frame = sender.TryGetNextFrame();
            tcs.TrySetResult(frame);
        };

        session.StartCapture();
        Console.WriteLine("[INFO] Capture started, waiting for frame (5s timeout)...");

        // Wait up to 5 seconds
        var timeoutTask = Task.Delay(5000);
        var completedTask = await Task.WhenAny(tcs.Task, timeoutTask);

        session.Dispose();

        if (completedTask == timeoutTask)
        {
            Console.WriteLine("[WARN] Frame capture timed out after 5 seconds.");
            pool.Dispose();
            return null;
        }

        var capturedFrame = await tcs.Task;
        if (capturedFrame == null)
        {
            pool.Dispose();
            return null;
        }

        // Convert Direct3D surface to SoftwareBitmap
        var bitmap = await SoftwareBitmap.CreateCopyFromSurfaceAsync(
            capturedFrame.Surface,
            BitmapAlphaMode.Premultiplied);

        capturedFrame.Dispose();
        pool.Dispose();

        return bitmap;
    }

    // ── Bitmap operations ────────────────────────────────────────────────

    private static SoftwareBitmap CropBitmap(SoftwareBitmap source, int x, int y, int w, int h)
    {
        // Convert to Bgra8 for byte manipulation
        var bgra = SoftwareBitmap.Convert(source, BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied);

        var cropped = new SoftwareBitmap(BitmapPixelFormat.Bgra8, w, h, BitmapAlphaMode.Premultiplied);

        // Use buffer access to copy pixel region
        using (var srcBuffer = bgra.LockBuffer(BitmapBufferAccessMode.Read))
        using (var dstBuffer = cropped.LockBuffer(BitmapBufferAccessMode.Write))
        using (var srcRef = srcBuffer.CreateReference())
        using (var dstRef = dstBuffer.CreateReference())
        {
            unsafe
            {
                var srcAccess = srcRef.As<IMemoryBufferByteAccess>();
                var dstAccess = dstRef.As<IMemoryBufferByteAccess>();

                srcAccess.GetBuffer(out byte* srcPtr, out uint srcCap);
                dstAccess.GetBuffer(out byte* dstPtr, out uint dstCap);

                var srcDesc = srcBuffer.GetPlaneDescription(0);
                var dstDesc = dstBuffer.GetPlaneDescription(0);

                for (int row = 0; row < h; row++)
                {
                    int srcOffset = srcDesc.StartIndex + ((y + row) * srcDesc.Stride) + (x * 4);
                    int dstOffset = dstDesc.StartIndex + (row * dstDesc.Stride);
                    int copyBytes = w * 4;

                    System.Buffer.MemoryCopy(srcPtr + srcOffset, dstPtr + dstOffset, dstCap - dstOffset, copyBytes);
                }
            }
        }

        bgra.Dispose();
        return cropped;
    }

    private static async Task SaveBitmapToPng(SoftwareBitmap bitmap, string path)
    {
        SoftwareBitmap bgraForSave = bitmap;
        bool createdNew = false;

        // Convert to Bgra8 for encoding ONLY if it is not already Bgra8 Premultiplied
        if (bitmap.BitmapPixelFormat != BitmapPixelFormat.Bgra8 || bitmap.BitmapAlphaMode != BitmapAlphaMode.Premultiplied)
        {
            bgraForSave = SoftwareBitmap.Convert(bitmap, BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied);
            createdNew = true;
        }

        using var stream = new InMemoryRandomAccessStream();
        var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId, stream);
        encoder.SetSoftwareBitmap(bgraForSave);
        await encoder.FlushAsync();

        // Write to file
        stream.Seek(0);
        using var fileStream = File.Create(path);
        var reader = new DataReader(stream);
        await reader.LoadAsync((uint)stream.Size);
        var bytes = new byte[stream.Size];
        reader.ReadBytes(bytes);
        await fileStream.WriteAsync(bytes);

        if (createdNew)
        {
            bgraForSave.Dispose();
        }
    }

    // ── CLI parsing ──────────────────────────────────────────────────────

    private static void ParseArgs(string[] args)
    {
        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--crop" when i + 1 < args.Length:
                    var parts = args[++i].Split(',');
                    if (parts.Length == 4)
                    {
                        cropX = double.Parse(parts[0]) / 100.0;
                        cropY = double.Parse(parts[1]) / 100.0;
                        cropW = double.Parse(parts[2]) / 100.0;
                        cropH = double.Parse(parts[3]) / 100.0;
                    }
                    Console.WriteLine($"[INFO] Custom crop: x={cropX*100}% y={cropY*100}% w={cropW*100}% h={cropH*100}%");
                    break;

                case "--output" when i + 1 < args.Length:
                    outputDir = args[++i];
                    Console.WriteLine($"[INFO] Output directory: {outputDir}");
                    break;
            }
        }
    }

    // ── Gate results ─────────────────────────────────────────────────────

    private static void PrintGateResults(bool capture, bool png, bool ocr, bool version)
    {
        Console.WriteLine();
        Console.WriteLine("╔══════════════════════════════════════════════════╗");
        Console.WriteLine("║  PHASE 0C GATE RESULTS                         ║");
        Console.WriteLine("╠══════════════════════════════════════════════════╣");
        Console.WriteLine($"║  0C.1 WGC capture under Vanguard:  {(capture ? "[PASS]" : "[FAIL]"),-10}    ║");
        Console.WriteLine($"║  0C.2 PNG saved and non-empty:     {(png     ? "[PASS]" : "[FAIL]"),-10}    ║");
        Console.WriteLine($"║  0C.3 OCR returned text:           {(ocr     ? "[PASS]" : "[FAIL]"),-10}    ║");
        Console.WriteLine($"║  0C.4 Windows build >= 19041:      {(version ? "[PASS]" : "[FAIL]"),-10}    ║");
        Console.WriteLine($"║  0C.5 Dynamic gameplay test:       [MANUAL]      ║");
        Console.WriteLine("╠══════════════════════════════════════════════════╣");

        bool allPassed = capture && png && ocr && version;
        if (allPassed)
        {
            Console.WriteLine("║  AUTOMATED GATES: ALL PASSED ✓                  ║");
            Console.WriteLine("║                                                  ║");
            Console.WriteLine("║  Next: Re-run while moving in Range to test      ║");
            Console.WriteLine("║  Gate 0C.5 (dynamic gameplay capture).           ║");
        }
        else
        {
            Console.WriteLine("║  GATE FAILED ✗                                   ║");
            Console.WriteLine("║                                                  ║");
            Console.WriteLine("║  DO NOT proceed with OCR sidecar.                ║");
            Console.WriteLine("║  Re-evaluate architecture.                       ║");
        }
        Console.WriteLine("╚══════════════════════════════════════════════════╝");
    }
}

// IMemoryBufferByteAccess COM interface for unsafe pixel access
[ComImport]
[Guid("5B0D3235-4DBA-4D44-865E-8F1D0E4FD04D")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal unsafe interface IMemoryBufferByteAccess
{
    void GetBuffer(out byte* buffer, out uint capacity);
}
