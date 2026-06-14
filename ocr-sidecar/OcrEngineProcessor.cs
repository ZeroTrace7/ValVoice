using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using Windows.Graphics.Imaging;
using Windows.Media.Ocr;

namespace ValVoiceOCR;

public record OcrLineResult(string Text, double Y, double X);

public class OcrEngineProcessor : IOcrEngineProcessor
{
    private OcrEngine? _ocrEngine;

    public OcrEngineProcessor()
    {
        _ocrEngine = OcrEngine.TryCreateFromUserProfileLanguages();
        if (_ocrEngine == null)
        {
            throw new Exception("Failed to initialize OcrEngine. Language not supported?");
        }
    }

    public async Task<IReadOnlyList<OcrLineResult>> ProcessFrameAsync(SoftwareBitmap frame)
    {
        if (_ocrEngine == null) return Array.Empty<OcrLineResult>();

        SoftwareBitmap bgraFrame = frame;
        bool createdNew = false;
        if (frame.BitmapPixelFormat != BitmapPixelFormat.Bgra8 || frame.BitmapAlphaMode != BitmapAlphaMode.Premultiplied)
        {
            bgraFrame = SoftwareBitmap.Convert(frame, BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied);
            createdNew = true;
        }

        try
        {
            var bounds = RegionCalculator.CalculateCropBounds(bgraFrame.PixelWidth, bgraFrame.PixelHeight);
            
            using var croppedBitmap = CropBitmap(bgraFrame, bounds.X, bounds.Y, bounds.Width, bounds.Height);
            
            var ocrResult = await _ocrEngine.RecognizeAsync(croppedBitmap);
            var lines = new List<OcrLineResult>();
            foreach (var line in ocrResult.Lines)
            {
                double y = 0, x = 0;
                if (line.Words.Count > 0)
                {
                    y = line.Words[0].BoundingRect.Y;
                    x = line.Words[0].BoundingRect.X;
                }
                lines.Add(new OcrLineResult(line.Text, y, x));
            }
            return lines;
        }
        finally
        {
            if (createdNew)
            {
                bgraFrame.Dispose();
            }
        }
    }

    private unsafe SoftwareBitmap CropBitmap(SoftwareBitmap source, int x, int y, int width, int height)
    {
        var target = new SoftwareBitmap(BitmapPixelFormat.Bgra8, width, height, BitmapAlphaMode.Premultiplied);

        using var srcBuffer = source.LockBuffer(BitmapBufferAccessMode.Read);
        using var srcRef = srcBuffer.CreateReference();
        var srcAccess = (IMemoryBufferByteAccess)Marshal.GetObjectForIUnknown(((WinRT.IWinRTObject)srcRef).NativeObject.ThisPtr);
        if (srcAccess == null) throw new Exception("Failed to cast source buffer");

        using var dstBuffer = target.LockBuffer(BitmapBufferAccessMode.Write);
        using var dstRef = dstBuffer.CreateReference();
        var dstAccess = (IMemoryBufferByteAccess)Marshal.GetObjectForIUnknown(((WinRT.IWinRTObject)dstRef).NativeObject.ThisPtr);
        if (dstAccess == null) throw new Exception("Failed to cast dest buffer");

        srcAccess.GetBuffer(out byte* srcPtr, out uint srcCapacity);
        dstAccess.GetBuffer(out byte* dstPtr, out uint dstCapacity);

        int srcDescStride = source.PixelWidth * 4;
        int dstDescStride = width * 4;

        for (int row = 0; row < height; row++)
        {
            byte* srcRow = srcPtr + ((y + row) * srcDescStride) + (x * 4);
            byte* dstRow = dstPtr + (row * dstDescStride);
            Buffer.MemoryCopy(srcRow, dstRow, dstDescStride, dstDescStride);
        }

        return target;
    }

    public void Dispose()
    {
        _ocrEngine = null;
    }
}
