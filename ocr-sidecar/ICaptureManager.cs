using System;
using Windows.Graphics.Imaging;

namespace ValVoiceOCR;

public interface ICaptureManager : IDisposable
{
    event EventHandler<SoftwareBitmap>? OnFrameCaptured;
    void StartCapture(IntPtr hwnd);
    void StopCapture();
}
