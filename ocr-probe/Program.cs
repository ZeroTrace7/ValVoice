using System.Diagnostics;
using System.Runtime.InteropServices;
using Windows.Graphics;
using Windows.Graphics.Capture;
using Windows.Graphics.DirectX;
using Windows.Graphics.DirectX.Direct3D11;
using Windows.Graphics.Imaging;
using Windows.Media.Ocr;
using Windows.Storage.Streams;

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
    // Win32 imports for window finding
    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr FindWindow(string? lpClassName, string lpWindowName);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsWindow(IntPtr hWnd);

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
        Console.WriteLine("║  OcrProbe — Phase 0C Feasibility Prototype      ║");
        Console.WriteLine("║  ValVoice OCR Migration Gate                    ║");
        Console.WriteLine("╚══════════════════════════════════════════════════╝");
        Console.WriteLine();

        // Parse args
        ParseArgs(args);

        // Check Windows version
        var osVer = Environment.OSVersion.Version;
        Console.WriteLine($"[INFO] Windows version: {osVer} (build {osVer.Build})");
        bool versionOk = osVer.Build >= 19041;
        if (!versionOk)
        {
            Console.WriteLine("[FAIL] Windows build < 19041. WGC requires Windows 10 2004+.");
            PrintGateResults(false, false, false, false);
            return 1;
        }
        Console.WriteLine("[OK]   Windows build >= 19041");
        Console.WriteLine();

        // Step 1: Find Valorant window
        Console.WriteLine("[STEP 1] Finding Valorant window...");
        IntPtr hwnd = FindValorantWindow();
        if (hwnd == IntPtr.Zero)
        {
            Console.WriteLine("[FAIL] Could not find Valorant window.");
            Console.WriteLine("       Make sure Valorant is running in Windowed or Borderless Windowed mode.");
            PrintGateResults(false, false, false, versionOk);
            return 1;
        }

        GetWindowRect(hwnd, out RECT windowRect);
        Console.WriteLine($"[OK]   Valorant HWND = 0x{hwnd:X} ({windowRect.Width}x{windowRect.Height})");
        Console.WriteLine();

        // Step 2: Create WGC capture
        Console.WriteLine("[STEP 2] Creating WGC capture via CreateForWindow(HWND)...");
        GraphicsCaptureItem? captureItem;
        bool captureCreated = false;
        try
        {
            captureItem = CaptureHelper.CreateItemForWindow(hwnd);
            captureCreated = true;
            Console.WriteLine($"[OK]   GraphicsCaptureItem created. Size: {captureItem.Size.Width}x{captureItem.Size.Height}");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[FAIL] WGC CreateForWindow failed: {ex.Message}");
            Console.WriteLine($"       HRESULT: 0x{ex.HResult:X8}");
            if (ex.HResult == unchecked((int)0x80070005))
                Console.WriteLine("       E_ACCESSDENIED — Vanguard may be blocking WGC.");
            PrintGateResults(false, false, false, versionOk);
            return 1;
        }
        Console.WriteLine();

        // Step 3: Create D3D device and capture one frame
        Console.WriteLine("[STEP 3] Setting up Direct3D11 device + frame pool...");
        IDirect3DDevice? d3dDevice = null;
        try
        {
            d3dDevice = CreateD3D11Device();
            Console.WriteLine("[OK]   Direct3D11 device created.");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[FAIL] D3D11 device creation failed: {ex.Message}");
            PrintGateResults(captureCreated, false, false, versionOk);
            return 1;
        }

        Console.WriteLine();
        Console.WriteLine("[STEP 4] Capturing one frame...");

        SoftwareBitmap? fullBitmap = null;
        bool frameCaptured = false;

        try
        {
            fullBitmap = await CaptureOneFrame(captureItem, d3dDevice, captureItem.Size);
            if (fullBitmap != null)
            {
                frameCaptured = true;
                Console.WriteLine($"[OK]   Frame captured: {fullBitmap.PixelWidth}x{fullBitmap.PixelHeight}");
            }
            else
            {
                Console.WriteLine("[FAIL] Frame capture returned null (timeout or empty).");
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[FAIL] Frame capture failed: {ex.Message}");
            Console.WriteLine($"       HRESULT: 0x{ex.HResult:X8}");
        }

        if (!frameCaptured || fullBitmap == null)
        {
            PrintGateResults(captureCreated, false, false, versionOk);
            return 1;
        }
        Console.WriteLine();

        // Step 5: Save full frame PNG
        Console.WriteLine("[STEP 5] Saving PNGs...");
        Directory.CreateDirectory(outputDir);

        string fullPath = Path.Combine(outputDir, "ocr_full.png");
        string cropPath = Path.Combine(outputDir, "ocr_crop.png");

        await SaveBitmapToPng(fullBitmap, fullPath);
        Console.WriteLine($"[OK]   Full frame saved: {fullPath} ({new FileInfo(fullPath).Length} bytes)");

        // Step 6: Crop chat region
        int cx = (int)(fullBitmap.PixelWidth * cropX);
        int cy = (int)(fullBitmap.PixelHeight * cropY);
        int cw = (int)(fullBitmap.PixelWidth * cropW);
        int ch = (int)(fullBitmap.PixelHeight * cropH);

        // Clamp to frame bounds
        cw = Math.Min(cw, fullBitmap.PixelWidth - cx);
        ch = Math.Min(ch, fullBitmap.PixelHeight - cy);

        Console.WriteLine($"[INFO] Crop region: x={cx}, y={cy}, w={cw}, h={ch} (from {cropX*100}%,{cropY*100}%,{cropW*100}%,{cropH*100}%)");

        bool pngSaved = false;
        SoftwareBitmap? croppedBitmap = null;

        if (cw > 0 && ch > 0)
        {
            croppedBitmap = CropBitmap(fullBitmap, cx, cy, cw, ch);
            await SaveBitmapToPng(croppedBitmap, cropPath);
            pngSaved = new FileInfo(cropPath).Length > 0;
            Console.WriteLine($"[OK]   Cropped region saved: {cropPath} ({new FileInfo(cropPath).Length} bytes)");
        }
        else
        {
            Console.WriteLine($"[FAIL] Crop region is empty or out of bounds.");
        }
        Console.WriteLine();

        // Step 7: Run OCR
        Console.WriteLine("[STEP 7] Running OCR on cropped region...");
        bool ocrSuccess = false;
        string ocrText = "";

        if (croppedBitmap != null)
        {
            try
            {
                var ocrEngine = OcrEngine.TryCreateFromLanguage(
                    new Windows.Globalization.Language("en-US"));

                if (ocrEngine == null)
                {
                    Console.WriteLine("[FAIL] OCR engine creation failed. Is en-US language pack installed?");
                    Console.WriteLine("       Settings → Time & Language → Language → Add English (United States) → OCR");
                }
                else
                {
                    // OcrEngine requires BGRA8 or Gray8
                    var ocrBitmap = SoftwareBitmap.Convert(croppedBitmap, BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied);
                    var result = await ocrEngine.RecognizeAsync(ocrBitmap);
                    ocrText = result.Text;
                    ocrSuccess = !string.IsNullOrWhiteSpace(ocrText);

                    Console.WriteLine();
                    Console.WriteLine("────────────── OCR OUTPUT ──────────────");
                    if (ocrSuccess)
                    {
                        foreach (var line in result.Lines)
                        {
                            Console.WriteLine($"  {line.Text}");
                        }
                    }
                    else
                    {
                        Console.WriteLine("  (empty — no text recognized)");
                    }
                    Console.WriteLine("────────────────────────────────────────");
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[FAIL] OCR failed: {ex.Message}");
            }
        }

        Console.WriteLine();

        // Step 8: Print gate results
        PrintGateResults(captureCreated, pngSaved, ocrSuccess, versionOk);

        // Cleanup
        fullBitmap?.Dispose();
        croppedBitmap?.Dispose();
        d3dDevice?.Dispose();

        return (captureCreated && pngSaved && ocrSuccess && versionOk) ? 0 : 1;
    }

    // ── Window detection ─────────────────────────────────────────────────

    private static IntPtr FindValorantWindow()
    {
        // Method 1: Find by process name
        var procs = Process.GetProcessesByName("VALORANT-Win64-Shipping");
        foreach (var proc in procs)
        {
            if (proc.MainWindowHandle != IntPtr.Zero)
            {
                Console.WriteLine($"[INFO] Found via process: PID={proc.Id}");
                return proc.MainWindowHandle;
            }
        }

        // Method 2: FindWindow by title
        IntPtr hwnd = FindWindow(null, "VALORANT");
        if (hwnd != IntPtr.Zero && IsWindow(hwnd))
        {
            Console.WriteLine($"[INFO] Found via FindWindow(\"VALORANT\")");
            return hwnd;
        }

        // Method 3: Try alternative title
        hwnd = FindWindow(null, "VALORANT  ");
        if (hwnd != IntPtr.Zero && IsWindow(hwnd))
        {
            Console.WriteLine($"[INFO] Found via FindWindow(\"VALORANT  \") (trailing spaces)");
            return hwnd;
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

        var device = MarshalInterface<IDirect3DDevice>.FromAbi(inspectable);

        // Release COM refs
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

        // Optional: suppress yellow border (non-blocking, version-gated)
        if (Environment.OSVersion.Version.Build >= 20348)
        {
            try
            {
                session.IsBorderRequired = false;
                Console.WriteLine("[INFO] IsBorderRequired set to false (build >= 20348)");
            }
            catch
            {
                Console.WriteLine("[INFO] IsBorderRequired not available (non-blocking)");
            }
        }

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
        {
            var srcRef = srcBuffer.CreateReference();
            var dstRef = dstBuffer.CreateReference();

            unsafe
            {
                ((IMemoryBufferByteAccess)srcRef).GetBuffer(out byte* srcPtr, out uint srcCap);
                ((IMemoryBufferByteAccess)dstRef).GetBuffer(out byte* dstPtr, out uint dstCap);

                var srcDesc = srcBuffer.GetPlaneDescription(0);
                var dstDesc = dstBuffer.GetPlaneDescription(0);

                for (int row = 0; row < h; row++)
                {
                    int srcOffset = srcDesc.StartIndex + ((y + row) * srcDesc.Stride) + (x * 4);
                    int dstOffset = dstDesc.StartIndex + (row * dstDesc.Stride);
                    int copyBytes = w * 4;

                    Buffer.MemoryCopy(srcPtr + srcOffset, dstPtr + dstOffset, dstCap - dstOffset, copyBytes);
                }
            }
        }

        bgra.Dispose();
        return cropped;
    }

    private static async Task SaveBitmapToPng(SoftwareBitmap bitmap, string path)
    {
        // Convert to Bgra8 for encoding
        var bgraForSave = SoftwareBitmap.Convert(bitmap, BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied);

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

        bgraForSave.Dispose();
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
