using System;
using System.Runtime.InteropServices;
using Windows.Graphics.Capture;
using Windows.Graphics.DirectX.Direct3D11;
using Windows.Graphics.Imaging;
using WinRT; // for .As<>()

namespace ValVoiceOCR;

public class CaptureManager : ICaptureManager
{
    private IDirect3DDevice? _device;
    private Direct3D11CaptureFramePool? _framePool;
    private GraphicsCaptureSession? _session;
    private GraphicsCaptureItem? _captureItem;
    
    public event EventHandler<SoftwareBitmap>? OnFrameCaptured;

    public void StartCapture(IntPtr hwnd)
    {
        if (_session != null) return;

        DiagnosticLogger.Log("Initializing WGC Capture Session...");

        // Create Direct3D11 device
        _device = CreateDirect3DDevice();

        // Create Capture Item
        _captureItem = CreateCaptureItem(hwnd);
        _captureItem.Closed += (s, e) => 
        {
            DiagnosticLogger.LogError("CaptureItem closed (window lost).");
            StopCapture();
        };

        // Create Frame Pool
        _framePool = Direct3D11CaptureFramePool.CreateFreeThreaded(
            _device,
            Windows.Graphics.DirectX.DirectXPixelFormat.B8G8R8A8UIntNormalized,
            2,
            _captureItem.Size);

        _framePool.FrameArrived += OnFrameArrived;

        // Start Session
        _session = _framePool.CreateCaptureSession(_captureItem);
        _session.StartCapture();
        DiagnosticLogger.Log($"Capture started for {_captureItem.Size.Width}x{_captureItem.Size.Height}");
    }

    private void OnFrameArrived(Direct3D11CaptureFramePool sender, object args)
    {
        var sw = System.Diagnostics.Stopwatch.StartNew();
        try
        {
            using Direct3D11CaptureFrame frame = sender.TryGetNextFrame();
            if (frame == null) return;

            var bitmapTask = SoftwareBitmap.CreateCopyFromSurfaceAsync(frame.Surface).AsTask();
            bitmapTask.Wait();
            
            SoftwareBitmap bitmap = bitmapTask.Result;
            
            OnFrameCaptured?.Invoke(this, bitmap);
        }
        catch (Exception ex)
        {
            Telemetry.RecordCaptureError();
            if (ex.Message.Contains("DeviceLost") || ex.HResult == unchecked((int)0x887A0005))
            {
                Telemetry.RecordDeviceLost();
            }
            DiagnosticLogger.LogError("Frame capture failed", ex);
        }
        sw.Stop();
        Telemetry.RecordFrameCaptured(sw.ElapsedMilliseconds);
    }

    public void StopCapture()
    {
        _session?.Dispose();
        _session = null;

        if (_framePool != null)
        {
            _framePool.FrameArrived -= OnFrameArrived;
            _framePool.Dispose();
            _framePool = null;
        }

        _captureItem = null;
        try { _device?.Dispose(); } catch { }
        if (_device != null && Marshal.IsComObject(_device)) { Marshal.ReleaseComObject(_device); }
        _device = null;
    }

    public void Dispose()
    {
        StopCapture();
    }

    private IDirect3DDevice CreateDirect3DDevice()
    {
        int hr = Interop.D3D11CreateDevice(IntPtr.Zero, 1 /* D3D_DRIVER_TYPE_HARDWARE */, IntPtr.Zero, 0x20 /* D3D11_CREATE_DEVICE_BGRA_SUPPORT */, IntPtr.Zero, 0, 7, out IntPtr d3dDevicePtr, out _, out IntPtr immediateContext);
        if (hr != 0) throw new Exception("D3D11CreateDevice failed");

        hr = Interop.CreateDirect3D11DeviceFromDXGIDevice(d3dDevicePtr, out IntPtr winrtDevicePtr);
        if (hr != 0) throw new Exception("CreateDirect3D11DeviceFromDXGIDevice failed");

        var device = WinRT.MarshalInterface<IDirect3DDevice>.FromAbi(winrtDevicePtr);
        Marshal.Release(winrtDevicePtr);
        Marshal.Release(d3dDevicePtr);
        if (immediateContext != IntPtr.Zero) Marshal.Release(immediateContext);

        if (device == null) throw new Exception("Failed to cast to IDirect3DDevice");
        return device;
    }

    private GraphicsCaptureItem CreateCaptureItem(IntPtr hwnd)
    {
        Guid WGC_IID = new Guid("79C3F95B-31F7-4EC2-A464-632EF5D30760");
        string factoryClassName = "Windows.Graphics.Capture.GraphicsCaptureItem";

        int hr = Interop.WindowsCreateString(factoryClassName, factoryClassName.Length, out IntPtr hstring);
        if (hr != 0) throw new Exception("WindowsCreateString failed");

        Guid activationFactoryGuid = new Guid("00000035-0000-0000-C000-000000000046");
        hr = Interop.RoGetActivationFactory(hstring, ref activationFactoryGuid, out IntPtr factoryPtr);
        Interop.WindowsDeleteString(hstring);
        if (hr != 0) throw new Exception("RoGetActivationFactory failed");

        var interop = Marshal.GetObjectForIUnknown(factoryPtr) as Interop.IGraphicsCaptureItemInterop;
        Marshal.Release(factoryPtr);
        if (interop == null) throw new Exception("Failed to cast to IGraphicsCaptureItemInterop");

        hr = interop.CreateForWindow(hwnd, ref WGC_IID, out IntPtr itemPtr);
        if (hr != 0) throw new Exception($"CreateForWindow failed with HRESULT 0x{hr:X8}");

        var item = WinRT.MarshalInterface<GraphicsCaptureItem>.FromAbi(itemPtr);
        Marshal.Release(itemPtr);

        if (item == null) throw new Exception("Failed to cast to GraphicsCaptureItem");
        return item;
    }
}
