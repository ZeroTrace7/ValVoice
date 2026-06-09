using System;
using System.Text.Json;

namespace ValVoiceOCR;

public static class JsonEmitter
{
    private static readonly object _lock = new object();

    public static void EmitChat(string channel, string name, string body)
    {
        var msg = new
        {
            type = "chat",
            channel = channel,
            name = name,
            body = body,
            timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
        };
        
        lock (_lock)
        {
            Console.WriteLine(JsonSerializer.Serialize(msg));
        }
    }

    public static void EmitDiagnostic(string eventName)
    {
        var msg = new
        {
            type = "diagnostic",
            @event = eventName
        };
        
        lock (_lock)
        {
            Console.WriteLine(JsonSerializer.Serialize(msg));
        }
    }
}
