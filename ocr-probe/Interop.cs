using System.Runtime.InteropServices;
using Windows.Graphics.Capture;

namespace OcrProbe;

/// <summary>
/// COM interop for IGraphicsCaptureItemInterop::CreateForWindow(HWND).
/// This is the ONLY permitted WGC creation path per the implementation plan.
/// TryCreateFromWindowId is BANNED (requires Win10 2104 + restricted capability).
/// </summary>
[ComImport]
[Guid("3628E81B-3CAC-4C60-B7F4-23CE0E0C3356")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IGraphicsCaptureItemInterop
{
    IntPtr CreateForWindow(
        [In] IntPtr window,
        [In] ref Guid iid,
        out IntPtr result);
}

internal static class CaptureHelper
{
    /// <summary>
    /// Create a GraphicsCaptureItem from a window handle using the Win32 interop path.
    /// </summary>
    public static GraphicsCaptureItem CreateItemForWindow(IntPtr hwnd)
    {
        var interop = GraphicsCaptureItem.As<IGraphicsCaptureItemInterop>();
        Guid iid = typeof(GraphicsCaptureItem).GetInterface("IGraphicsCaptureItem")?.GUID
                    ?? new Guid("79C3F95B-31F7-4EC2-A464-632EF5D30760"); // IGraphicsCaptureItem GUID
        interop.CreateForWindow(hwnd, ref iid, out IntPtr raw);
        var item = MarshalInterface<GraphicsCaptureItem>.FromAbi(raw);
        return item;
    }
}
