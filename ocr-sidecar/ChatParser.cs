using System.Text.RegularExpressions;

namespace ValVoiceOCR;

public static class ChatParser
{
    // Matches: (Team) Player: hello
    // Matches: (All) Player: hello
    // Matches: (Party) Player: hello
    // Also tolerates OCR noise like '( Team )' or missing spaces.
    private static readonly Regex _chatRegex = new Regex(
        @"^\s*\(?\s*(Team|All|Party|System)\s*\)?\s*([^:]+):\s*(.+)$",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    public static (string Channel, string Name, string Body)? Parse(string rawText)
    {
        if (string.IsNullOrWhiteSpace(rawText)) return null;

        // Clean common OCR noise from lines
        string cleaned = rawText.Replace("\n", " ").Replace("\r", "").Trim();

        var match = _chatRegex.Match(cleaned);
        if (match.Success)
        {
            string channel = match.Groups[1].Value.Trim().ToUpperInvariant();
            string name = match.Groups[2].Value.Trim();
            string body = match.Groups[3].Value.Trim();

            // Ignore system messages from self or noise
            if (name.Length == 0 || body.Length == 0) return null;

            return (channel, name, body);
        }

        return null;
    }
}
