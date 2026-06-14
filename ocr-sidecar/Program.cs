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

        _ = Task.Run(async () =>
        {
            await foreach (var frame in channel.Reader.ReadAllAsync(cts.Token))
            {
                try
                {
                    using (frame)
                    {
                        var ocrLines = await ocrProcessor.ProcessFrameAsync(frame);
                        bool isEmpty = ocrLines.Count == 0;

                        // Diagnostic: log all lines with coordinates
                        for (int i = 0; i < ocrLines.Count; i++)
                        {
                            DiagnosticLogger.Log($"Line[{i}]: Y={ocrLines[i].Y:F0} X={ocrLines[i].X:F0} TEXT={ocrLines[i].Text}");
                        }

                        foreach (var ocrLine in ocrLines)
                        {
                            var parsed = ChatParser.Parse(ocrLine.Text);
                            if (parsed != null)
                            {
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
                                Telemetry.RecordOcrExecution(false, false);
                            }
                        }

                        if (isEmpty)
                        {
                            Telemetry.RecordOcrExecution(false, true);
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
