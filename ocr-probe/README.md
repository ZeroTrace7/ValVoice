# OcrProbe — Phase 0C Feasibility Prototype

Standalone C# console app that validates WGC capture + OCR under Vanguard.

## Prerequisites

- .NET 8 SDK
- Valorant running in **Windowed** or **Borderless Windowed** mode
- Chat visible on screen (type something in chat first)

## Build & Run

```powershell
cd ocr-probe
$env:PATH = "C:\Users\HP\AppData\Local\dotnet-sdk;$env:PATH"
dotnet build
dotnet run
```

With custom crop region (x%, y%, w%, h%):
```powershell
dotnet run -- --crop 0,70,30,25
```

With custom output directory:
```powershell
dotnet run -- --output C:\MyDir
```

## Gate Criteria

All must pass before proceeding to Phase 1:

| Gate | Test | Required |
|------|------|----------|
| 0C.1 | No HRESULT error from WGC on Vanguard-protected Valorant | YES |
| 0C.2 | PNG crop is non-empty and shows visible chat area | YES |
| 0C.3 | OCR output contains at least one recognizable ASCII word | YES |
| 0C.4 | Runs on Windows 10 build 19041+ without API-not-found exceptions | YES |
| 0C.5 | Capture while moving in Range with chat open — OCR still reads | YES (manual) |

## If Gate Fails

**STOP.** Do not proceed with OCR sidecar. Re-evaluate architecture.

## Output Files

- `C:\Temp\ocr_full.png` — Full captured frame
- `C:\Temp\ocr_crop.png` — Cropped chat region
