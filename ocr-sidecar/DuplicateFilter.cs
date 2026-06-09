using System;
using System.Collections.Generic;
using System.Linq;

namespace ValVoiceOCR;

public class DuplicateFilter
{
    private readonly Dictionary<string, DateTime> _cache = new();
    private readonly TimeSpan _ttl = TimeSpan.FromSeconds(30);
    private readonly object _lock = new object();

    public bool IsNewMessage(string channel, string name, string body)
    {
        string key = $"{channel}|{name}|{body}";
        DateTime now = DateTime.UtcNow;

        lock (_lock)
        {
            // Prune expired entries
            var expiredKeys = _cache.Where(kvp => now - kvp.Value > _ttl).Select(kvp => kvp.Key).ToList();
            foreach (var k in expiredKeys)
            {
                _cache.Remove(k);
            }

            if (_cache.ContainsKey(key))
            {
                _cache[key] = now; // update TTL
                return false;
            }

            _cache[key] = now;
            return true;
        }
    }
}
