using System;

namespace ValVoiceOCR;

public static class DiagnosticLogger
{
    private static readonly object _lock = new object();

    public static void Log(string message)
    {
        lock (_lock)
        {
            Console.Error.WriteLine($"[{DateTime.UtcNow:HH:mm:ss.fff}] {message}");
        }
    }

    public static void LogError(string message, Exception? ex = null)
    {
        lock (_lock)
        {
            Console.Error.WriteLine($"[{DateTime.UtcNow:HH:mm:ss.fff}] [ERROR] {message}");
            if (ex != null)
            {
                Console.Error.WriteLine(ex.ToString());
            }
        }
    }
}
