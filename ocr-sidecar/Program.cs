using System;
using System.Threading;
using System.Threading.Tasks;

namespace ValVoiceOCR;

class Program
{
    static async Task Main(string[] args)
    {
        AppDomain.CurrentDomain.UnhandledException += (s, e) =>
        {
            DiagnosticLogger.LogError("Unhandled exception in sidecar", e.ExceptionObject as Exception);
            Environment.Exit(1);
        };

        try
        {
            ChatParserTests.RunAll();
        }
        catch (Exception ex)
        {
            DiagnosticLogger.LogError("Mandatory ChatParser tests failed. Refusing to start.", ex);
            return;
        }

        DiagnosticLogger.Log("ValVoice OCR Sidecar starting...");
        DiagnosticLogger.Log("=== OCR INSTRUMENTATION BUILD ACTIVE ===");
        Telemetry.Initialize();

        using var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (s, e) => 
        {
            DiagnosticLogger.Log("Shutdown requested.");
            e.Cancel = true;
            cts.Cancel();
        };

        IWindowDetector detector = new WindowDetector();
        using ICaptureManager captureManager = new CaptureManager();
        using IOcrEngineProcessor ocrProcessor = new OcrEngineProcessor();
        DuplicateFilter filter = new DuplicateFilter();

        IntPtr currentHwnd = IntPtr.Zero;

        var channel = System.Threading.Channels.Channel.CreateBounded<Windows.Graphics.Imaging.SoftwareBitmap>(
            new System.Threading.Channels.BoundedChannelOptions(2)
            {
                FullMode = System.Threading.Channels.BoundedChannelFullMode.DropOldest
            },
            droppedItem =>
            {
                Telemetry.RecordQueueDrop();
                droppedItem.Dispose();
            });

        // Phase B2: Y-Coordinate Freshness Audit — instrumentation only
        int frameNumber = 0;
        var lastSeenY = new Dictionary<string, double>();
        var lastSeenFrame = new Dictionary<string, int>();

        _ = Task.Run(async () =>
        {
            await foreach (var frame in channel.Reader.ReadAllAsync(cts.Token))
            {
                try
                {
                    using (frame)
                    {
                        frameNumber++;
                        var ocrLines = await ocrProcessor.ProcessFrameAsync(frame);
                        bool isEmpty = ocrLines.Count == 0;

                        // Diagnostic: log all lines with coordinates
                        for (int i = 0; i < ocrLines.Count; i++)
                        {
                            DiagnosticLogger.Log($"Line[{i}]: Y={ocrLines[i].Y:F0} X={ocrLines[i].X:F0} TEXT={ocrLines[i].Text}");
                        }

                        var parseableYValues = new List<double>();

                        var linesToProcess = ocrLines.Count > 0 
                            ? new[] { ocrLines.OrderByDescending(l => l.Y).First() } 
                            : Array.Empty<OcrLineResult>();

                        if (ocrLines.Count > 0)
                        {
                            var allY = string.Join(",", ocrLines.OrderBy(l => l.Y).Select(l => $"{l.Y:F0}"));
                            var selected = linesToProcess[0];
                            DiagnosticLogger.Log($"FRAME={frameNumber}");
                            DiagnosticLogger.Log($"ALL_Y=[{allY}]");
                            DiagnosticLogger.Log($"SELECTED_Y={selected.Y:F0}");
                            DiagnosticLogger.Log($"SELECTED_TEXT={selected.Text}");
                        }

                        foreach (var ocrLine in linesToProcess)
                        {
                            var parsed = ChatParser.Parse(ocrLine.Text);
                            if (parsed != null)
                            {
                                var key = $"{parsed.Value.Channel}|{parsed.Value.Name}|{parsed.Value.Body}";
                                DiagnosticLogger.Log($"[PARSE] FRAME={frameNumber} Y={ocrLine.Y:F0} TEXT='{ocrLine.Text}' PARSED=true KEY='{key}'");
                                parseableYValues.Add(ocrLine.Y);

                                // Freshness tracking
                                if (lastSeenY.ContainsKey(key))
                                {
                                    double oldY = lastSeenY[key];
                                    int oldFrame = lastSeenFrame[key];
                                    double deltaY = ocrLine.Y - oldY;
                                    int deltaFrame = frameNumber - oldFrame;
                                    DiagnosticLogger.Log($"[FRESHNESS] FRAME={frameNumber} BODY='{parsed.Value.Body}' OLD_Y={oldY:F0} NEW_Y={ocrLine.Y:F0} DELTA_Y={deltaY:F0} DELTA_FRAME={deltaFrame} STATUS=RESEEN");
                                }
                                else
                                {
                                    DiagnosticLogger.Log($"[FRESHNESS] FRAME={frameNumber} BODY='{parsed.Value.Body}' Y={ocrLine.Y:F0} STATUS=FIRST_SEEN");
                                }
                                lastSeenY[key] = ocrLine.Y;
                                lastSeenFrame[key] = frameNumber;

                                Telemetry.RecordOcrExecution(true, false);
                                if (filter.IsNewMessage(parsed.Value.Channel, parsed.Value.Name, parsed.Value.Body))
                                {
                                    JsonEmitter.EmitChat(parsed.Value.Channel, parsed.Value.Name, parsed.Value.Body);
                                }
                                else
                                {
                                    Telemetry.RecordDuplicateSuppression();
                                }
                            }
                            else
                            {
                                DiagnosticLogger.Log($"[PARSE] FRAME={frameNumber} Y={ocrLine.Y:F0} TEXT='{ocrLine.Text}' PARSED=false");
                                Telemetry.RecordOcrExecution(false, false);
                            }
                        }

                        // Per-frame summary
                        if (parseableYValues.Count > 0)
                        {
                            var yStr = string.Join(",", parseableYValues.Select(y => $"{y:F0}"));
                            DiagnosticLogger.Log($"=== FRAME SUMMARY ===");
                            DiagnosticLogger.Log($"FRAME={frameNumber}");
                            DiagnosticLogger.Log($"PARSEABLE_LINES={parseableYValues.Count}");
                            DiagnosticLogger.Log($"Y_VALUES=[{yStr}]");
                            DiagnosticLogger.Log($"=====================");
                        }

                        if (isEmpty)
                        {
                            Telemetry.RecordOcrExecution(false, true);
                        }

                        if (frameNumber % 30 == 0)
                        {
                            DiagnosticLogger.Log($"[HEARTBEAT] Frame={frameNumber} Flowing. LinesThisFrame={ocrLines.Count}");
                        }
                    }
                }
                catch (OperationCanceledException) { }
                catch (Exception ex)
                {
                    DiagnosticLogger.LogError("Pipeline processing failed", ex);
                }
            }
        });

        captureManager.OnFrameCaptured += (s, frame) =>
        {
            Telemetry.UpdateQueueDepth(channel.Reader.Count + 1);
            if (!channel.Writer.TryWrite(frame))
            {
                Telemetry.RecordQueueDrop();
                frame.Dispose();
            }
        };

        JsonEmitter.EmitDiagnostic("window_searching");
        while (!cts.IsCancellationRequested)
        {
            IntPtr hwnd = detector.FindWindow();

            if (hwnd != currentHwnd)
            {
                if (hwnd != IntPtr.Zero)
                {
                    DiagnosticLogger.Log($"Found VALORANT window: HWND 0x{hwnd:X}");
                    JsonEmitter.EmitDiagnostic("window_found");
                    currentHwnd = hwnd;
                    captureManager.StartCapture(hwnd);
                }
                else if (currentHwnd != IntPtr.Zero)
                {
                    DiagnosticLogger.Log("Lost VALORANT window.");
                    JsonEmitter.EmitDiagnostic("window_lost");
                    captureManager.StopCapture();
                    currentHwnd = IntPtr.Zero;
                }
            }

            await Task.Delay(2000, cts.Token).ContinueWith(_ => {});
        }

        captureManager.StopCapture();
        JsonEmitter.EmitDiagnostic("ocr_stopped");
        Telemetry.PrintSummary();
        DiagnosticLogger.Log("Sidecar exited cleanly.");
    }
}
