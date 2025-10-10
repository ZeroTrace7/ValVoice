#!/usr/bin/env node
/**
 * ValVoice XMPP Bridge (stub)
 * This is a placeholder. Replace with a real Riot XMPP client implementation to get live messages.
 * It emits JSON lines to stdout mimicking the expected protocol.
 */
function emit(obj) {
  try { process.stdout.write(JSON.stringify(obj) + "\n"); } catch (_) {}
}

emit({ type: 'startup', pid: process.pid, ts: Date.now(), version: '0.0.2-stub' });

setTimeout(() => {
  emit({
    type: 'incoming',
    time: Date.now(),
    data: "<iq type='result' id='_xmpp_bind1'><bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'><jid>playerSelf@prod.na1.chat.valorant.gg/resource</jid></bind></iq>"
  });
}, 1500);

const sampleMessages = [
  "<message from='ally123@ares-parties.na1.pvp.net' type='groupchat'><body>Hey team from PARTY</body></message>",
  "<message from='enemyall@ares-coregame.na1.pvp.net' type='groupchat'><body>All chat hello</body></message>",
  "<message from='friend987@ares-coregame.na1.pvp.net' type='groupchat'><body>Need rotate?</body></message>",
  "<message from='whisperGuy@prod.na1.chat.valorant.gg' type='chat'><body>&amp;Encoded &lt;Whisper&gt;</body></message>"
];
let idx = 0;
const msgTimer = setInterval(() => {
  if (idx < sampleMessages.length) emit({ type: 'incoming', time: Date.now(), data: sampleMessages[idx++] });
}, 4000);

const hb = setInterval(() => emit({ type: 'heartbeat', ts: Date.now() }), 10000);

setTimeout(() => emit({ type: 'error', time: Date.now(), error: 'Simulated transient XMPP warning' }), 20000);
setTimeout(() => emit({ type: 'close-valorant', time: Date.now() }), 35000);

function shutdown(reason) {
  try { clearInterval(hb); clearInterval(msgTimer); } catch (_) {}
  emit({ type: 'shutdown', reason, ts: Date.now() });
  process.exit(0);
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('uncaughtException', (err) => {
  emit({ type: 'error', error: err.message, stack: err.stack, ts: Date.now() });
  process.exit(1);
});

