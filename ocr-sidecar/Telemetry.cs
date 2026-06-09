using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Linq;
using System.Threading;

namespace ValVoiceOCR;

public static class Telemetry
{
    public static readonly Stopwatch Runtime = new Stopwatch();
    
    private static long _framesCaptured;
    private static long _ocrExecutions;
    private static long _ocrSuccesses;
    private static long _ocrEmptyResults;
    private static long _duplicateSuppressions;
    private static long _queueDrops;
    private static int _peakQueueDepth;
    private static long _deviceLostCount;
    private static long _captureErrorCount;

    private static readonly ConcurrentBag<long> _frameTimings = new();

    public static long StartMemory { get; private set; }

    public static void Initialize()
    {
        using var p = Process.GetCurrentProcess();
        StartMemory = p.WorkingSet64;
        Runtime.Start();
    }

    public static void RecordFrameCaptured(long ms)
    {
        Interlocked.Increment(ref _framesCaptured);
        _frameTimings.Add(ms);
    }

    public static void RecordOcrExecution(bool success, bool empty)
    {
        Interlocked.Increment(ref _ocrExecutions);
        if (success) Interlocked.Increment(ref _ocrSuccesses);
        if (empty) Interlocked.Increment(ref _ocrEmptyResults);
    }

    public static void RecordDuplicateSuppression() => Interlocked.Increment(ref _duplicateSuppressions);
    public static void RecordQueueDrop() => Interlocked.Increment(ref _queueDrops);
    
    public static void UpdateQueueDepth(int currentDepth)
    {
        int initial;
        do
        {
            initial = _peakQueueDepth;
            if (currentDepth <= initial) break;
        } while (Interlocked.CompareExchange(ref _peakQueueDepth, currentDepth, initial) != initial);
    }

    public static void RecordDeviceLost() => Interlocked.Increment(ref _deviceLostCount);
    public static void RecordCaptureError() => Interlocked.Increment(ref _captureErrorCount);

    public static void PrintSummary()
    {
        Runtime.Stop();
        using var p = Process.GetCurrentProcess();
        long endMemory = p.WorkingSet64;
        long peakMemory = p.PeakWorkingSet64;

        var timings = _frameTimings.ToArray();
        double avg = 0;
        long p95 = 0;
        long max = 0;

        if (timings.Length > 0)
        {
            Array.Sort(timings);
            avg = timings.Average();
            max = timings.Last();
            int p95Index = (int)Math.Floor(timings.Length * 0.95);
            if (p95Index >= timings.Length) p95Index = timings.Length - 1;
            p95 = timings[p95Index];
        }

        DiagnosticLogger.Log("=========================================");
        DiagnosticLogger.Log("       FINAL VALIDATION SUMMARY          ");
        DiagnosticLogger.Log("=========================================");
        DiagnosticLogger.Log($"Runtime duration       : {Runtime.Elapsed}");
        DiagnosticLogger.Log($"Frames captured        : {_framesCaptured}");
        DiagnosticLogger.Log($"FrameArrived Avg       : {avg:F2} ms");
        DiagnosticLogger.Log($"FrameArrived P95       : {p95} ms");
        DiagnosticLogger.Log($"FrameArrived Max       : {max} ms");
        DiagnosticLogger.Log($"OCR executions         : {_ocrExecutions}");
        DiagnosticLogger.Log($"OCR successes          : {_ocrSuccesses}");
        DiagnosticLogger.Log($"OCR empty results      : {_ocrEmptyResults}");
        DiagnosticLogger.Log($"Duplicate suppressions : {_duplicateSuppressions}");
        DiagnosticLogger.Log($"Queue drops            : {_queueDrops}");
        DiagnosticLogger.Log($"Peak queue depth       : {_peakQueueDepth}");
        DiagnosticLogger.Log($"Start memory           : {StartMemory / 1024 / 1024} MB");
        DiagnosticLogger.Log($"Peak memory            : {peakMemory / 1024 / 1024} MB");
        DiagnosticLogger.Log($"End memory             : {endMemory / 1024 / 1024} MB");
        DiagnosticLogger.Log($"Device lost count      : {_deviceLostCount}");
        DiagnosticLogger.Log($"Capture error count    : {_captureErrorCount}");
        DiagnosticLogger.Log("=========================================");
    }
}
