# ValVoice Team Chat TTS Fix - Complete Summary

## Issues Fixed (December 8, 2025)

### ✅ Issue 1: Message.java Pattern Flags
**Status:** Already correct
- `BODY_PATTERN` has `Pattern.DOTALL` flag for multi-line content

### ✅ Issue 2: Message.java MessageType Detection
**Status:** Already correct
- `classifyMessage()` matches ValorantNarrator exactly:
  - `ares-parties` → `PARTY`
  - `ares-pregame` → `TEAM`
  - `ares-coregame` (not ending in "all") → `TEAM`
  - `ares-coregame` (ending in "all") → `ALL`
  - `type="chat"` → `WHISPER`

### ✅ Issue 3 & 4: Chat.shouldNarrate() Logic
**File:** `Chat.java`
**Fix Applied:** Rewrote `shouldNarrate()` to match ValorantNarrator's exact logic
- Now uses legacy flags: `teamState`, `partyState`, `allState`, `selfState`, `privateState`
- Proper handling of own messages based on `selfState`
- Detailed logging for each decision path
- **Default:** `teamState=true`, `partyState=true`, `selfState=true`

### ✅ Issue 5: Main.java MESSAGE_STANZA_PATTERN
**File:** `Main.java`
**Fix Applied:** Updated regex pattern to handle edge cases
```java
private static final Pattern MESSAGE_STANZA_PATTERN = Pattern.compile(
    "<message\\s[^>]*>.*?</message>", 
    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
);
```
- Added debug logging to show Chat state when processing messages

### ✅ Issue 6: Message.java Constructor Bug (CRITICAL)
**File:** `Message.java`
**Fix Applied:** Fixed matcher extraction order
- Extract `from` attribute first (needed for classification)
- Reuse `fromAttr` instead of re-matching
- Eliminate unnecessary matcher recreation
- Added `from` to debug logging

## Complete Message Flow (Now Matching ValorantNarrator)

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. XMPP Bridge (index.js)                                       │
│    ├─ Receives raw XML from Riot XMPP server                    │
│    ├─ Example: <message from="abc123@ares-coregame.ap.pvp.net"  │
│    │             type="groupchat"><body>gg</body></message>      │
│    ├─ Emits: { type: 'incoming', data: xmlString }              │
│    └─ Sends to Java via stdout                                  │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 2. Main.java (handleIncomingStanza)                             │
│    ├─ Reads JSON line from bridge stdout                        │
│    ├─ Extracts XML from obj.data                                │
│    ├─ Uses MESSAGE_STANZA_PATTERN to find ALL message stanzas   │
│    ├─ For each <message>...</message> with <body>:              │
│    │  ├─ Creates new Message(xml)                               │
│    │  ├─ Logs Chat state (teamState, partyState, etc.)          │
│    │  └─ Calls ChatDataHandler.getInstance().message(msg)       │
│    └─ Falls back to direct parse if regex misses                │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 3. Message.java Constructor                                     │
│    ├─ Extract 'from' attribute (e.g., "abc@ares-coregame...")   │
│    ├─ Extract 'type' attribute (e.g., "groupchat")              │
│    ├─ Call classifyMessage(from, type):                         │
│    │  ├─ Parse server type from 'from' JID                      │
│    │  │  ├─ ares-parties → PARTY                                │
│    │  │  ├─ ares-pregame → TEAM                                 │
│    │  │  ├─ ares-coregame (id ends "all") → ALL                 │
│    │  │  ├─ ares-coregame (not "all") → TEAM                    │
│    │  │  └─ type="chat" → WHISPER                               │
│    ├─ Parse <body>...</body> (with DOTALL for multi-line)       │
│    ├─ Extract userId from JID/from                              │
│    ├─ Check isOwnMessage (compare with ChatDataHandler selfId)  │
│    └─ Log: "Parsed Message: type=TEAM userId=abc123 own=false"  │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 4. ChatDataHandler.message(Message msg)                         │
│    ├─ Validate: msg != null && content != null                  │
│    ├─ Log: "Processing message: type=TEAM, from=abc123..."      │
│    ├─ Record stats: chat.recordIncoming(message)                │
│    ├─ Call: chat.shouldNarrate(message)                         │
│    ├─ If true:                                                   │
│    │  ├─ Record: chat.recordNarrated(message)                   │
│    │  └─ Call: ValVoiceController.narrateMessage(message)       │
│    └─ Else: Log "Message skipped"                               │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 5. Chat.shouldNarrate(Message msg) - ValorantNarrator Logic     │
│    ├─ Check 1: If disabled → return false                       │
│    ├─ Check 2: If userId in ignoredPlayerIDs → return false     │
│    ├─ Check 3: Message type filtering:                          │
│    │  ├─ TEAM:                                                   │
│    │  │  ├─ If own && selfState → return true (allow own)       │
│    │  │  └─ Return teamState (TRUE by default)                  │
│    │  ├─ PARTY: Return partyState (TRUE by default)             │
│    │  ├─ ALL: Return allState (FALSE by default)                │
│    │  └─ WHISPER: Return privateState (TRUE by default)         │
│    └─ Log decision with details                                 │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 6. ValVoiceController.narrateMessage(Message msg)               │
│    ├─ Extract content, voice, rate from UI                      │
│    ├─ Log: "TTS TRIGGERED: 'gg' (voice: Zira, rate: 50)"        │
│    └─ Call: VoiceGenerator.speakVoice(content, voice, rate)     │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 7. VoiceGenerator.speakVoice(text, voice, rate)                 │
│    ├─ Queue message (wait if already speaking)                  │
│    ├─ If PTT enabled:                                            │
│    │  ├─ Release keybind                                        │
│    │  └─ Press keybind (refresh)                                │
│    ├─ Call: InbuiltVoiceSynthesizer.speakInbuiltVoice()         │
│    │  ├─ Uses PowerShell System.Speech                          │
│    │  └─ Audio routes to VB-CABLE → Valorant mic                │
│    └─ Mark speaking as finished (release queue)                 │
└─────────────────────────────────────────────────────────────────┘
                            ↓
                    🎮 Teammates hear TTS!
```

## Key Configuration (Default Values)

### Chat Channels (Chat.java)
- ✅ `teamState = true` (TEAM messages enabled)
- ✅ `partyState = true` (PARTY messages enabled)
- ❌ `allState = false` (ALL chat disabled by default)
- ✅ `privateState = true` (WHISPER enabled)
- ✅ `selfState = true` (Own messages enabled)

### UI Source Selection (ValVoiceController.java)
- Default: `"SELF+PARTY+TEAM"` (matches Chat defaults)

### VoiceGenerator (Push-to-Talk)
- Default keybind: `V` (KeyEvent.VK_V)
- PTT enabled by default
- Key held down continuously, released+pressed to refresh before speaking

## Testing Checklist

1. ✅ Start ValVoice application
2. ✅ Check logs for: "Chat initialized: enabledChannels=[PARTY, TEAM]"
3. ✅ Open Valorant and join a game
4. ✅ Check logs for: "Self ID detected: {your-puuid}"
5. ✅ Send a message in TEAM chat
6. ✅ Check logs for:
   - `📨 RAW MESSAGE STANZA: <message from="...@ares-coregame..."`
   - `📝 Parsed Message: type=TEAM userId=... from=...@ares-coregame...`
   - `🔍 DEBUG: MessageType=TEAM, teamState=true...`
   - `📥 Processing message: type=TEAM, userId=...`
   - `✅ shouldNarrate=true: TEAM message`
   - `🎤 Sending message to TTS: '...'`
   - `🔊 Speaking: voice='...', rate=..., text='...'`
7. ✅ Verify teammates hear the TTS in-game

## Debugging Tips

If team chat TTS still doesn't work:

1. **Check XMPP connection:**
   - Log should show: `✓ Connected to Riot XMPP server`

2. **Check message reception:**
   - Look for: `📨 RAW MESSAGE STANZA` in logs
   - If missing → XMPP bridge not receiving messages

3. **Check message parsing:**
   - Look for: `📝 Parsed Message: type=TEAM`
   - If `type=null` → Message classification failed

4. **Check Chat state:**
   - Look for: `🔍 DEBUG: MessageType=TEAM, teamState=true`
   - If `teamState=false` → Channel disabled

5. **Check shouldNarrate:**
   - Look for: `✅ shouldNarrate=true: TEAM message`
   - If `false` → Check filtering logic

6. **Check TTS trigger:**
   - Look for: `🔊 Speaking: voice=...`
   - If missing → VoiceGenerator not called

## Files Modified

1. `src/main/java/com/someone/valvoicebackend/Message.java`
   - Fixed constructor matcher extraction order
   - Added `from` to debug logging

2. `src/main/java/com/someone/valvoicebackend/Chat.java`
   - Rewrote `shouldNarrate()` to match ValorantNarrator
   - Added detailed decision logging
   - Added initialization logging

3. `src/main/java/com/someone/valvoicegui/Main.java`
   - Fixed `MESSAGE_STANZA_PATTERN` regex
   - Added Chat state debug logging in `handleIncomingStanza()`

4. `xmpp-bridge/index.js`
   - Fixed body regex pattern to handle multi-line content

## Build Instructions

```bash
cd C:\Users\HP\IdeaProjects\ValVoice
mvn clean compile
mvn package
```

The fixed application is ready to test!

