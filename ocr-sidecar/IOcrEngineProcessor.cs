using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Windows.Graphics.Imaging;

namespace ValVoiceOCR;

public interface IOcrEngineProcessor : IDisposable
{
    Task<IReadOnlyList<OcrLineResult>> ProcessFrameAsync(SoftwareBitmap frame);
}
