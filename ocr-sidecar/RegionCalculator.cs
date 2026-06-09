using System;

namespace ValVoiceOCR;

public static class RegionCalculator
{
    // Evaluated from Phase 0C 
    private const double CropX = 0.0;
    private const double CropY = 0.70;
    private const double CropW = 0.30;
    private const double CropH = 0.25;

    public static (int X, int Y, int Width, int Height) CalculateCropBounds(int fullWidth, int fullHeight)
    {
        int cx = (int)(fullWidth * CropX);
        int cy = (int)(fullHeight * CropY);
        int cw = (int)(fullWidth * CropW);
        int ch = (int)(fullHeight * CropH);
        
        cw = Math.Min(cw, fullWidth - cx);
        ch = Math.Min(ch, fullHeight - cy);

        return (cx, cy, cw, ch);
    }
}
