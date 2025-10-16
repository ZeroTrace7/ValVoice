# Chat Message Testing Guide

## Current Status Analysis

Based on your logs:
```
23:45:25.191 [xmpp-node-io] DEBUG ... Connected to Riot XMPP server ✅
23:45:25.200 [xmpp-node-io] DEBUG ... Requesting roster and chats... ✅
23:45:25.205 [xmpp-node-io] DEBUG ... Sending presence... ✅
23:45:25.561 [xmpp-node-io] INFO ... [IQ] received ✅
23:45:25.581 [xmpp-node-io] INFO ... [PRESENCE] received ✅
```

**GOOD NEWS:**
- ✅ XMPP is fully connected to Riot's servers
- ✅ Authentication succeeded
- ✅ Receiving PRESENCE stanzas (player status updates)
- ✅ Receiving IQ stanzas (roster/configuration responses)

**WHAT'S MISSING:**
- ❌ No `[MESSAGE]` stanzas in logs
- ❌ This means no chat messages are being sent/received

## Why You Don't See Chat Messages

### Reason 1: Not in an Active Game/Party
XMPP only receives messages when you're actively in:
- A Valorant party (pre-game lobby with friends)
- An active match (in-game team/all chat)
- A private message conversation

**Solution:** Join a party or start a match

### Reason 2: Channel Filters
Your current settings:
```
23:46:17.544 ... channels=[PARTY, TEAM] includeOwn=true whispersEnabled=true
```

This means you're listening to:
- ✅ PARTY chat (pre-game lobby with friends)
- ✅ TEAM chat (in-game team chat)
- ❌ ALL chat (disabled - enemy team messages)
- ✅ Your own messages (includeOwn=true)

**If someone sends in ALL chat, you won't see it.** Change to `PARTY+TEAM+ALL` to see everything.

### Reason 3: No Messages Being Sent
If nobody is typing in chat, there's nothing to receive!

## How to Test Chat Messages

### Test 1: Party Chat (Easiest)
1. Launch Valorant
2. Create a party or join a friend's party
3. Type a message in party chat
4. Check ValVoice logs for:
   ```
   [xmpp-node-io] INFO ... [MESSAGE] from ...
   [RiotInitThread] INFO ... Received message: (PARTY)...
   ```

### Test 2: In-Game Team Chat
1. Start a Valorant match (Unrated/Deathmatch)
2. Press ENTER to open chat
3. Type a message (default goes to TEAM chat)
4. Press ENTER to send
5. Check ValVoice logs

### Test 3: All Chat
1. In a match, press SHIFT+ENTER
2. Type a message to ALL chat
3. Press ENTER to send
4. Make sure ValVoice source is set to include ALL:
   - Settings → Source → Select "PARTY+TEAM+ALL"

### Test 4: Whisper/Private Message
1. Right-click a friend's name
2. Select "Whisper"
3. Type a message
4. Check logs for WHISPER message type

## Expected Log Output When Working

When a chat message is received, you should see:

```
[xmpp-node-io] INFO ... [MESSAGE] from ares-parties@jp1.pvp.net: Test message
[RiotInitThread] INFO ... Received message: (PARTY)d69bef3d-5960-5176-89bc-4c1168bc4126@jp1.pvp.net: Test message
[JavaFX Application Thread] INFO ... Narrating message: Test message
```

## Quick Diagnostic Checklist

- [ ] Valorant is running
- [ ] Riot Client is running (ValVoice shows "Connected" status)
- [ ] You're in a party OR active match
- [ ] Someone sent a chat message (or you sent one)
- [ ] Message channel (PARTY/TEAM/ALL) matches your ValVoice source filter
- [ ] User who sent message is not in your ignore list

## Current Settings from Your Log

```
Source: SELF+PARTY+TEAM (you changed it at 23:46:17)
```

This means:
- ✅ You'll see your own messages
- ✅ You'll see PARTY chat
- ✅ You'll see TEAM chat
- ❌ You WON'T see ALL chat (enemy messages)
- ✅ You'll see WHISPER/private messages

## Recommended Test Procedure

### Step 1: Simple Party Test
1. Keep ValVoice running
2. Open Valorant
3. Go to Main Menu → Social → Create Party
4. Open party chat (click the chat icon)
5. Type: "test"
6. Press ENTER
7. Check ValVoice console/logs

**Expected:** You should see `[MESSAGE]` in logs with your test message

### Step 2: In-Game Test
1. Start a Deathmatch (quick, no commitment)
2. Press ENTER
3. Type: "hello team"
4. Press ENTER
5. Check ValVoice logs

**Expected:** Message appears in logs, TTS reads "hello team"

### Step 3: Enable All Chat
1. In ValVoice Settings → Source → Select "PARTY+TEAM+ALL"
2. In Valorant match, press SHIFT+ENTER
3. Type: "testing all chat"
4. Press ENTER
5. Check logs

## Understanding PRESENCE vs MESSAGE Stanzas

**PRESENCE stanzas** (what you're seeing now):
- Player online/offline status
- Friend availability updates
- Game state changes (in menu, in match, etc.)
- Sent continuously even when idle

**MESSAGE stanzas** (what you need):
- Actual chat messages
- Only sent when someone types in chat
- Contains sender, channel, and message body

## If Messages Still Don't Appear

### Check 1: XMPP Connection Quality
Look for these in logs:
```
✅ Connected to Riot XMPP server
✅ Requesting roster and chats...
✅ Sending presence...
```

### Check 2: Java Message Handler
Look for:
```
[RiotInitThread] INFO ... Received message: ...
```

If you see `[MESSAGE]` in XMPP logs but NOT in RiotInitThread, there's a parsing issue.

### Check 3: Self ID Resolution
From your logs:
```
23:45:47.336 ... Resolved self player ID: d69bef3d-5960-5176-89bc-4c1168bc4126 ✅
```

This is correct. Self ID is needed to filter your own messages.

## Advanced: Force a Test Message (Code)

You can test TTS without Valorant by adding this to ValVoiceController:

```java
// In Settings panel, add a "Test TTS" button that calls:
Chat.getInstance().recordIncoming(new Message(
    "<message type='chat' from='test@jp1.pvp.net'><body>Test TTS message</body></message>"
));
```

This bypasses XMPP and directly tests the TTS pipeline.

## Summary

Your XMPP is **working perfectly**. You're just not in a situation where chat messages are being sent.

**Next steps:**
1. Join a Valorant party
2. Send a message in party chat
3. Watch ValVoice logs for `[MESSAGE]` stanza
4. TTS should read your message

**If you want to test TTS without waiting for chat:**
- Install VB-Audio Cable (see VB_AUDIO_SETUP_GUIDE.md)
- Use Settings panel to test voice synthesis
- Or add a manual test button (requires code change)

