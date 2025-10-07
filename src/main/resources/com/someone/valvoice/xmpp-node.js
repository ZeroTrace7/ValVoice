#!/usr/bin/env node
/**
 * Xmpp-Node placeholder.
 * This script is a stub; replace implementation with real Riot XMPP handling.
 * It outputs JSON lines to stdout for the Java process to consume.
 */

function emit(obj) {
  try {
    process.stdout.write(JSON.stringify(obj) + '\n');
  } catch (e) {
    // Swallow
  }
}

emit({ type: 'startup', pid: process.pid, ts: Date.now(), version: '0.0.2-stub' });

// Simulate XMPP bind response (self JID) after short delay
setTimeout(() => {
  emit({
    type: 'incoming',
    time: Date.now(),
    data: "<iq type='result' id='_xmpp_bind1'><bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'><jid>playerSelf@prod.na1.chat.valorant.gg/resource</jid></bind></iq>"
  });
}, 1500);

// Simulated incoming chat messages
const sampleMessages = [
  "<message from='ally123@ares-parties.na1.pvp.net' type='groupchat'><body>Hey team from PARTY</body></message>",
  "<message from='enemyall@ares-coregame.na1.pvp.net' type='groupchat'><body>All chat hello</body></message>",
  "<message from='friend987@ares-coregame.na1.pvp.net' type='groupchat'><body>Need rotate?</body></message>",
  "<message from='whisperGuy@prod.na1.chat.valorant.gg' type='chat'><body>&amp;Encoded &lt;Whisper&gt;</body></message>"
];
let idx = 0;
setInterval(() => {
  if (idx < sampleMessages.length) {
    emit({ type: 'incoming', time: Date.now(), data: sampleMessages[idx++] });
  }
}, 4000);

// Periodic heartbeat
const hb = setInterval(() => {
  emit({ type: 'heartbeat', ts: Date.now() });
}, 10000);

// Simulate an error event after some time
setTimeout(() => {
  emit({ type: 'error', time: Date.now(), error: 'Simulated transient XMPP warning' });
}, 20000);

// Simulate Valorant client closing (for demonstration) after 35s
setTimeout(() => {
  emit({ type: 'close-valorant', time: Date.now() });
}, 35000);

process.on('SIGINT', () => {
  emit({ type: 'shutdown', reason: 'SIGINT', ts: Date.now() });
  clearInterval(hb);
  process.exit(0);
});
process.on('SIGTERM', () => {
  emit({ type: 'shutdown', reason: 'SIGTERM', ts: Date.now() });
  clearInterval(hb);
  process.exit(0);
});

process.on('uncaughtException', (err) => {
  emit({ type: 'error', error: err.message, stack: err.stack, ts: Date.now() });
  process.exit(1);
});
