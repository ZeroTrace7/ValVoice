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
internal interface IGraphicsCaptureItemInterop
{
    [PreserveSig]
    int CreateForWindow(
        IntPtr hwnd,
        ref Guid iid,
        out IntPtr result);
}

internal static class CaptureHelper
{
    [DllImport("combase.dll", PreserveSig = false)]
    private static extern void RoGetActivationFactory(
        [MarshalAs(UnmanagedType.HString)] string activatableClassId,
        [In] ref Guid iid,
        out IntPtr factory);

    /// <summary>
    /// Create a GraphicsCaptureItem from a window handle.
    /// Uses RoGetActivationFactory → IGraphicsCaptureItemInterop → CreateForWindow(HWND).
    /// </summary>
    public static GraphicsCaptureItem CreateItemForWindow(IntPtr hwnd)
    {
        // 1. Get the activation factory as IGraphicsCaptureItemInterop
        var interopIid = new Guid("3628E81B-3CAC-4C60-B7F4-23CE0E0C3356");
        RoGetActivationFactory(
            "Windows.Graphics.Capture.GraphicsCaptureItem",
            ref interopIid,
            out IntPtr factoryPtr);

        var interop = (IGraphicsCaptureItemInterop)
            Marshal.GetTypedObjectForIUnknown(factoryPtr, typeof(IGraphicsCaptureItemInterop));
        Marshal.Release(factoryPtr);

        // 2. Create capture item — IGraphicsCaptureItem IID
        var itemIid = new Guid("79C3F95B-31F7-4EC2-A464-632EF5D30760");
        int hr = interop.CreateForWindow(hwnd, ref itemIid, out IntPtr itemPtr);
        Marshal.ThrowExceptionForHR(hr);

        // 3. Marshal to managed object and release unmanaged ref
        var item = (GraphicsCaptureItem)Marshal.GetObjectForIUnknown(itemPtr);
        Marshal.Release(itemPtr);

        return item;
    }
}
