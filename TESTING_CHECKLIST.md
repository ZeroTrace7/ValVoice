# ValVoice TTS Testing Checklist

## ✅ Build Status
- [x] Java project rebuilt (20:59:22)
- [x] XMPP bridge rebuilt (21:00:24)
- [x] Diagnostic logging enabled

## 🧪 Testing Steps

### 1. Start ValVoice
- [ ] Run the application
- [ ] Wait for XMPP status to show "Connected"
- [ ] Check that game state monitoring shows your party ID

### 2. Verify Settings
- [ ] Open ValVoice UI
- [ ] Check that **Party** chat is ENABLED (checkbox checked)
- [ ] Check that **Team** chat is ENABLED (checkbox checked)
- [ ] If testing your own messages: Enable "Include Own Messages"
- [ ] Voice selected in dropdown
- [ ] VB-Cable configured (if using Push-to-Talk)

### 3. Send Test Messages

#### Test 1: Party Chat (Easiest to Test)
- [ ] Be in a party with at least one other player
- [ ] Type in party chat: "test message one"
- [ ] Press Enter to send
- [ ] Check logs immediately

**Expected Logs:**
```
📨 RAW MESSAGE STANZA: <message...
✅ MESSAGE HAS BODY - will attempt to parse and narrate
📝 Parsed Message: type=PARTY userId=... body='test message one'
📥 Processing message: type=PARTY, userId=..., content='test message one'
📊 shouldNarrate decision: true for message type=PARTY
🎤 Sending message to TTS: 'test message one'
🔊 TTS TRIGGERED: "test message one"
```

#### Test 2: Team Chat (In Agent Select)
- [ ] Queue for a match
- [ ] During agent select, type in team chat: "hello team"
- [ ] Press Enter
- [ ] Check logs

**Expected:** Same log pattern as above, but type=TEAM

#### Test 3: In-Game Team Chat
- [ ] During a match, press Enter
- [ ] Type: "rush B"
- [ ] Press Enter
- [ ] Check logs

#### Test 4: All Chat (In-Game)
- [ ] Enable **All** chat in ValVoice settings
- [ ] During match, press Shift+Enter
- [ ] Type: "glhf"
- [ ] Press Enter
- [ ] Check logs

**Expected:** Same log pattern, but type=ALL

## 📊 What to Look For

### ✅ Success Indicators
- `📨 RAW MESSAGE STANZA:` appears in logs
- `✅ MESSAGE HAS BODY` appears
- Message gets parsed and classified
- `🔊 TTS TRIGGERED:` appears
- You hear TTS output

### ❌ Failure Indicators

#### Symptom 1: No Message Stanzas Received
**If you see:**
- Only presence stanzas
- No `📨 RAW MESSAGE STANZA:` logs

**This means:** 
- XMPP is not receiving chat messages
- Party chat might not use XMPP MUC
- Need to investigate Riot local API alternative

#### Symptom 2: Message Received but Not Narrated
**If you see:**
- `📨 RAW MESSAGE STANZA:` ✓
- `📥 Processing message` ✓
- `📊 shouldNarrate decision: false`

**Check:**
- Is the channel enabled in UI?
- Is "Include Own Messages" enabled (if testing your own)?
- Is message type classified correctly?

#### Symptom 3: TTS Not Triggering
**If you see:**
- `🎤 Sending message to TTS:` ✓
- But no `🔊 TTS TRIGGERED:`

**Check:**
- VoiceGenerator initialization
- Voice selection in UI
- InbuiltVoiceSynthesizer ready state

## 🔍 After Testing

### If It Works
1. Share which chat type worked
2. Confirm TTS audio is heard
3. Test with teammates to verify they hear it

### If It Doesn't Work
**Share these logs:**
1. Full application output from start
2. Timestamp when you sent test message
3. Which chat channel you used
4. Complete log section from 10 seconds before to 10 seconds after sending

**Critical info to note:**
- Did you see ANY `📨 RAW MESSAGE STANZA:` entries?
- Did you see `📨 [MESSAGE]` or `📨 [MUC CHAT]` in console error logs?
- What is your game state (lobby/pregame/in-game)?
- Are you in a party or solo?

## 🚨 Known Limitations

1. **Party chat may not work via XMPP** - Valorant might use a different protocol
2. **In-game chat requires being in an actual match** - Practice mode might work differently
3. **Rate limiting** - Sending too many messages quickly might be rate-limited

## 💡 Quick Troubleshooting

### No Connection
- Ensure Valorant is running
- Check Riot Client is running
- Restart ValVoice

### No Party Messages
- Try in-game team chat instead
- Party chat might need alternative approach

### No TTS Audio
- Check Windows audio mixer
- Verify VB-Cable is installed
- Check Valorant input device is set to VB-Cable

---

**Good luck with testing! Remember to actually SEND messages, don't just be in chat.**

