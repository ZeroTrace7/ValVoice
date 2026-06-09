using System;
using WinRT;
using Windows.Graphics.Capture;

namespace OcrProbe;

public class TestWinRT
{
    public static void Test()
    {
        IntPtr p = IntPtr.Zero;
        var item = MarshalInterface<GraphicsCaptureItem>.FromAbi(p);
    }
}
