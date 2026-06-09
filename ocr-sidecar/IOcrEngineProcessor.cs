using System;
using System.Threading.Tasks;
using Windows.Graphics.Imaging;

namespace ValVoiceOCR;

public interface IOcrEngineProcessor : IDisposable
{
    Task<string> ProcessFrameAsync(SoftwareBitmap frame);
}
