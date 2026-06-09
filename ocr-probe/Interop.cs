using System.Runtime.InteropServices;
using Windows.Graphics.Capture;

namespace OcrProbe;

/// <summary>
/// COM interop for IGraphicsCaptureItemInterop::CreateForWindow(HWND).
/// 
/// CRITICAL: The COM signature returns HRESULT (int), not IntPtr.
/// Using [PreserveSig] + int return is mandatory. Without it, the CLR
/// marshaler misinterprets the return value and crashes with
/// "Invalid managed/unmanaged type combination".
/// </summary>
[ComImport]
[Guid("3628E81B-3CAC-4C60-B7F4-23CE0E0C3356")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IMyGraphicsCaptureItemInterop
{
    [PreserveSig]
    int CreateForWindow(
        [In] IntPtr hwnd,
        [In] ref Guid iid,
        out IntPtr result);
}

internal static class CaptureHelper
{
    [DllImport("combase.dll", ExactSpelling = true)]
    private static extern int WindowsCreateString(
        [MarshalAs(UnmanagedType.LPWStr)] string sourceString,
        int length,
        out IntPtr hstring);

    [DllImport("combase.dll", ExactSpelling = true)]
    private static extern int WindowsDeleteString(IntPtr hstring);

    [DllImport("combase.dll", ExactSpelling = true)]
    private static extern int RoGetActivationFactory(
        IntPtr activatableClassId,
        ref Guid iid,
        out IntPtr factory);

    /// <summary>
    /// Create a GraphicsCaptureItem from a window handle.
    /// Uses RoGetActivationFactory → IGraphicsCaptureItemInterop → CreateForWindow(HWND).
    /// </summary>
    public static GraphicsCaptureItem CreateItemForWindow(IntPtr hwnd)
    {
        // 1. Get the activation factory as IGraphicsCaptureItemInterop
        string className = "Windows.Graphics.Capture.GraphicsCaptureItem";
        int hrCreate = WindowsCreateString(className, className.Length, out IntPtr hstring);
        Marshal.ThrowExceptionForHR(hrCreate);

        var interopIid = new Guid("3628E81B-3CAC-4C60-B7F4-23CE0E0C3356");
        int hrRo = RoGetActivationFactory(hstring, ref interopIid, out IntPtr factoryPtr);
        
        WindowsDeleteString(hstring);
        Marshal.ThrowExceptionForHR(hrRo);

        var interop = (IMyGraphicsCaptureItemInterop)
            Marshal.GetTypedObjectForIUnknown(factoryPtr, typeof(IMyGraphicsCaptureItemInterop));
        Marshal.Release(factoryPtr);

        // 2. Create capture item — IGraphicsCaptureItem IID
        var itemIid = new Guid("79C3F95B-31F7-4EC2-A464-632EF5D30760");
        int hr = interop.CreateForWindow(hwnd, ref itemIid, out IntPtr itemPtr);
        Marshal.ThrowExceptionForHR(hr);

        // 3. Marshal to managed object and release unmanaged ref
        var item = WinRT.MarshalInterface<GraphicsCaptureItem>.FromAbi(itemPtr);
        Marshal.Release(itemPtr);

        return item;
    }
}
