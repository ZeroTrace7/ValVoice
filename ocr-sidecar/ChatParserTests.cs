using System;

namespace ValVoiceOCR;

public static class ChatParserTests
{
    public static void RunAll()
    {
        DiagnosticLogger.Log("Running mandatory ChatParser tests...");

        TestValid("(Team) Player: hello", "TEAM", "Player", "hello");
        TestValid("(All) Player: hello", "ALL", "Player", "hello");
        TestValid("(Party) Player: hello", "PARTY", "Player", "hello");
        TestValid("(System) Brave xd: has invited you to join", "SYSTEM", "Brave xd", "has invited you to join");
        
        // Tolerances
        TestValid("( Team ) Player Name: hello world", "TEAM", "Player Name", "hello world");
        TestValid("Team Player: missing parens", "TEAM", "Player", "missing parens");

        // Malformed noise
        TestInvalid("Just some random noise");
        TestInvalid("(Team) :");
        TestInvalid("Player: missing channel");

        DiagnosticLogger.Log("ChatParser tests passed.");
    }

    private static void TestValid(string input, string expectedChannel, string expectedName, string expectedBody)
    {
        var result = ChatParser.Parse(input);
        if (result == null)
            throw new Exception($"Test failed: Expected valid parse for '{input}', but got null.");

        if (result.Value.Channel != expectedChannel || result.Value.Name != expectedName || result.Value.Body != expectedBody)
            throw new Exception($"Test failed: Mismatched parse for '{input}'. Got ({result.Value.Channel}, {result.Value.Name}, {result.Value.Body}).");
    }

    private static void TestInvalid(string input)
    {
        var result = ChatParser.Parse(input);
        if (result != null)
            throw new Exception($"Test failed: Expected invalid parse for '{input}', but got success.");
    }
}
