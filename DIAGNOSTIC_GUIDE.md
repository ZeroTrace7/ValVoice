# ValVoice TTS Diagnostic Guide

## Current Issue
TTS (Text-to-Speech) is not being triggered when chat messages are sent in Valorant party/team/all chat.

## Changes Made

### Enhanced Logging

I've added comprehensive logging to help diagnose the issue:

#### 1. XMPP Bridge (index.js)
- **Enhanced message detection** to capture ALL message types:
  - MUC messages (team/all chat in-game)
  - Direct messages (party chat, whispers)
  - Messages without body (typing indicators, receipts)
- **Detailed logging** shows:
  - Message type (chat, groupchat, etc.)
  - Sender (from attribute)
  - Message ID
  - Body content preview

**Key Log Patterns:**
```
📨 [MESSAGE] type=chat from=user@jp1.pvp.net id=msg-123
📨 [MUC CHAT] type=groupchat from=roomid@ares-coregame.jp1.pvp.net: "Hello team"
📨 [DIRECT CHAT] type=chat from=friend@jp1.pvp.net: "Hi there"
```

#### 2. Main.java
- Added logging for incoming message stanzas
- Shows when a message has `<body>` content

**Key Log Patterns:**
```
📨 RAW MESSAGE STANZA: <message from='...' type='chat'><body>Hello</body></message>
✅ MESSAGE HAS BODY - will attempt to parse and narrate
```

#### 3. ChatDataHandler.java
- Logs every message being processed
- Shows `shouldNarrate` decision and why
- Logs when message is sent to TTS

**Key Log Patterns:**
```
📥 Processing message: type=PARTY, userId=abc123, content='Hello', isOwn=false
📊 shouldNarrate decision: true for message type=PARTY
🎤 Sending message to TTS: 'Hello'
```

#### 4. Message.java
- Logs parsed message details
- Shows message type classification

**Key Log Patterns:**
```
📝 Parsed Message: type=PARTY userId=abc123 own=false body='Hello'
🔍 Classifying message: serverType='ares-parties', idPart='...', typeAttr='chat'
✅ Classified as PARTY (from 52c82682...@ares-parties.jp1.pvp.net)
```

#### 5. Chat.java (shouldNarrate)
- Shows detailed filtering decision

**Key Log Patterns:**
```
📊 shouldNarrate=true for type=PARTY | enabledChannels=[PARTY, TEAM] | includeOwn=true | whispers=true | isOwn=false
❌ shouldNarrate=false: own message but includeOwnMessages=false
```

## Current Diagnosis

Based on the logs you provided earlier:

### What's Working ✅
1. XMPP connection established
2. Party room detected (`partyId=52c82682-fb29-4da9-a5c0-474ab90d9aa5`)
3. Game state monitoring working (checks every 5 seconds)
4. Room tracking working ("Same party room, no rejoin needed")

### What's NOT Working ❌
1. **NO actual chat messages are being received**
   - Logs only show presence stanzas (player status updates)
   - No `📨 RAW MESSAGE STANZA:` entries
   - No `📨 [MUC CHAT]` or `📨 [DIRECT CHAT]` entries
   - The "Ignored stanza with body/chat" logs are for PRESENCE stanzas, not messages

### Key Insight
The presence stanzas contain `<st>chat</st>` which means the player's status is "chat" (they're in the chat system), NOT that a chat message was sent.

## Testing Steps

### 1. Verify Builds Completed
Check that:
- Maven build completed: `target/valvoice-1.0.0.jar` exists
- XMPP bridge rebuilt: `valvoice-xmpp.exe` exists

### 2. Start the Application
Run ValVoice and ensure it connects to XMPP.

### 3. Send Test Messages
**IMPORTANT:** You must actually TYPE and SEND messages in Valorant chat:

#### Party Chat Test
1. Be in a party with friends
2. Open party chat (default: /party in chat)
3. Type a message and press Enter
4. OR have a party member send a message

#### Team Chat Test (In-Game)
1. Be in a match
2. Press Enter to open team chat
3. Type a message and press Enter

#### All Chat Test (In-Game)
1. Be in a match
2. Press Shift+Enter to open all chat
3. Type a message and press Enter

### 4. Check the Logs

Look for these patterns in order:

**If working correctly, you should see:**
```
📨 RAW MESSAGE STANZA: <message...
✅ MESSAGE HAS BODY - will attempt to parse and narrate
📝 Parsed Message: type=PARTY userId=... body='...'
📥 Processing message: type=PARTY, userId=..., content='...', isOwn=...
📊 shouldNarrate decision: true for message type=PARTY
🎤 Sending message to TTS: '...'
🔊 TTS TRIGGERED: "..." (voice: ..., rate: ..., PTT: ...)
```

**If messages are NOT being received, you'll see:**
- Only presence logs
- No `📨 RAW MESSAGE STANZA:` entries
- No message processing logs

## Possible Issues & Solutions

### Issue 1: Party Chat Uses Different Protocol
**Symptom:** No messages appear in logs when sending party chat

**Possible Cause:** Valorant party chat may not use XMPP MUC rooms. It might use:
- Direct XMPP messages between users
- Riot local API message system
- In-game proprietary protocol

**Solution:** Monitor Riot local API endpoints for chat messages

### Issue 2: Messages Filtered Out
**Symptom:** Messages appear in logs but `shouldNarrate=false`

**Check:**
1. Channel is enabled in UI (Party/Team/All checkboxes)
2. "Include Own Messages" setting if testing with your own messages
3. User is not in ignore list

### Issue 3: TTS Not Initialized
**Symptom:** `shouldNarrate=true` but no TTS output

**Check logs for:**
```
⚠ VoiceGenerator not initialized - using direct TTS (no Push-to-Talk)
⚠ TTS system not ready - cannot narrate message!
⚠ No voices available - cannot narrate message!
```

**Solution:** Check InbuiltVoiceSynthesizer and VoiceGenerator initialization

## Next Debugging Steps

If messages still don't appear after testing:

### 1. Monitor Riot Local API
The Riot Client API may have chat endpoints we can poll:
- `GET https://127.0.0.1:{port}/chat/v4/messages`
- `GET https://127.0.0.1:{port}/chat/v5/conversations`

### 2. Check XMPP Stream
Add raw XMPP stream logging to see ALL data, not just parsed stanzas.

### 3. Test Different Chat Types
Test each chat type separately:
- Whisper (direct message to friend)
- Party chat (in lobby)
- Team chat (pregame agent select)
- Team chat (in-game)
- All chat (in-game)

### 4. Verify XMPP Room Format
The room JID format should be:
- Party: `{partyId}@ares-parties.{region}.pvp.net`
- Pregame: `{pregameId}@ares-pregame.{region}.pvp.net`
- In-game (team): `{matchId}@ares-coregame.{region}.pvp.net`
- In-game (all): `{matchId}all@ares-coregame.{region}.pvp.net`

Your region is `jp1`, so rooms should end with `.jp1.pvp.net`.

## Expected Behavior After Fix

Once working:
1. Send message in Valorant chat
2. Within 1 second, see message in logs
3. Hear TTS output through VB-Cable
4. Teammates hear the TTS if Open Mic is enabled

## Contact Points

If you need to share logs, include:
- Full log output from application start
- Timestamp when test message was sent
- Which chat channel was used (party/team/all)
- Whether you sent the message or received it

---

**Last Updated:** 2025-12-08
**ValVoice Version:** 1.0.0
**Diagnostic Version:** 2.0

