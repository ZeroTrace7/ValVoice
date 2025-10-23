#!/usr/bin/env node
/**
 * ValVoice XMPP Bridge - Real Riot XMPP Client
 * Based on valorant-xmpp-watcher implementation
 * Connects to Riot Games XMPP service for Valorant chat
 * FIXED: Now includes MUC room joining for team/party/all chat
 */

const tls = require('tls');
const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');

// Output helper - sends JSON lines to stdout for Java app to consume
function emit(obj) {
  try {
    process.stdout.write(JSON.stringify(obj) + '\n');
  } catch (e) {
    process.stderr.write(`Emit error: ${e.message}\n`);
  }
}

let xmppSocket = null;
let reconnectTimer = null;
let heartbeatTimer = null;
let isShuttingDown = false;

// Track joined MUC rooms to avoid duplicate joins
const joinedRooms = new Set();
let currentPuuid = null; // Store puuid for room joining

emit({ type: 'startup', pid: process.pid, ts: Date.now(), version: '2.3.0-muc-fixed' });

// Default UA used for Riot endpoints that sometimes reject empty UA
const DEFAULT_UA = 'ValVoice-XMPP/2.3 (Windows; Node.js)';

// Disable global keep-alive to prevent connection reuse issues
http.globalAgent.keepAlive = false;
https.globalAgent.keepAlive = false;

/**
 * Make HTTPS request using native module with retry logic
 */
function httpsRequest(url, options = {}) {
  return new Promise((resolve, reject) => {
    const urlObj = new URL(url);

    // Create a fresh agent for each request to avoid connection reuse issues
    const agent = new https.Agent({
      keepAlive: false,
      maxSockets: 1,
      maxFreeSockets: 0,
      timeout: options.timeout || 60000,
      scheduling: 'fifo'
    });

    const reqOptions = {
      hostname: urlObj.hostname,
      port: urlObj.port || 443,
      path: urlObj.pathname + urlObj.search,
      method: options.method || 'GET',
      headers: {
        'User-Agent': DEFAULT_UA,
        'Connection': 'close',
        ...(options.headers || {})
      },
      rejectUnauthorized: options.rejectUnauthorized !== false,
      agent: agent
    };

    const req = https.request(reqOptions, (res) => {
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => {
        // Cleanup agent after request
        agent.destroy();

        let parsedData = data;
        if (data.trim()) {
          try {
            parsedData = JSON.parse(data);
          } catch (e) {
            parsedData = data.trim();
          }
        }
        resolve({ statusCode: res.statusCode, headers: res.headers, data: parsedData });
      });
    });

    req.on('error', (err) => {
      agent.destroy();
      reject(err);
    });

    req.setTimeout(options.timeout || 60000, () => {
      req.destroy();
      agent.destroy();
      reject(new Error('Request timeout'));
    });

    if (options.body) {
      req.setHeader('Content-Length', Buffer.byteLength(options.body));
      req.write(options.body);
    }
    req.end();
  });
}

/**
 * Retry wrapper for network requests
 */
async function retryRequest(requestFn, maxRetries = 5, baseDelay = 2000) {
  let lastError;
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      return await requestFn();
    } catch (error) {
      lastError = error;
      const isRetryable = error.code === 'ECONNRESET' ||
                          error.code === 'ECONNREFUSED' ||
                          error.code === 'ETIMEDOUT' ||
                          error.code === 'EPIPE' ||
                          error.code === 'ENOTFOUND' ||
                          error.code === 'EAI_AGAIN' ||
                          error.message.includes('timeout') ||
                          error.message.includes('socket hang up');

      if (attempt < maxRetries && isRetryable) {
        const delay = baseDelay * Math.pow(1.5, attempt - 1);
        emit({ type: 'debug', message: `Request failed (${error.code || error.message}), retrying in ${delay}ms (attempt ${attempt}/${maxRetries})`, ts: Date.now() });
        await new Promise(r => setTimeout(r, delay));
      } else {
        break;
      }
    }
  }
  throw lastError;
}

/**
 * Helper: poll local chat session until loaded or timeout
 */
async function waitForChatSession(authHeader, baseUrl, timeoutMs = 30000) {
  const started = Date.now();
  let attempt = 0;
  while (Date.now() - started < timeoutMs) {
    attempt++;
    const chatResponse = await httpsRequest(`${baseUrl}/chat/v1/session`, {
      headers: { 'Authorization': authHeader },
      rejectUnauthorized: false
    });
    emit({ type: 'debug', message: `Chat session response: status=${chatResponse.statusCode}, loaded=${chatResponse.data?.loaded}`, ts: Date.now() });
    if (chatResponse.statusCode === 200 && chatResponse.data && chatResponse.data.loaded) {
      return chatResponse.data;
    }
    // Backoff a bit before retry
    await new Promise(r => setTimeout(r, Math.min(500 + attempt * 500, 3000)));
  }
  return null;
}

/**
 * Get auth credentials from local Riot Client lockfile
 */
async function getLocalRiotAuth() {
  try {
    const lockfilePath = path.join(
      process.env.LOCALAPPDATA || process.env.APPDATA,
      'Riot Games/Riot Client/Config/lockfile'
    );

    if (!fs.existsSync(lockfilePath)) {
      emit({ type: 'error', error: 'Lockfile not found. Is Riot Client running?', ts: Date.now() });
      return null;
    }

    const lockfileData = fs.readFileSync(lockfilePath, 'utf8');
    const [name, pid, port, password, protocol] = lockfileData.split(':');

    emit({ type: 'debug', message: `Lockfile: port=${port}, protocol=${protocol}`, ts: Date.now() });

    const basicAuth = Buffer.from(`riot:${password}`).toString('base64');
    const baseUrl = `${protocol}://127.0.0.1:${port}`;
    const authHeader = `Basic ${basicAuth}`;

    // First, get the entitlements token which contains the proper access token
    const entitlementsResponse = await httpsRequest(`${baseUrl}/entitlements/v1/token`, {
      headers: { 'Authorization': authHeader },
      rejectUnauthorized: false
    });

    emit({ type: 'debug', message: `Entitlements response: status=${entitlementsResponse.statusCode}`, ts: Date.now() });

    if (entitlementsResponse.statusCode !== 200 || !entitlementsResponse.data) {
      emit({ type: 'error', error: `Failed to get entitlements. Status: ${entitlementsResponse.statusCode}`, ts: Date.now() });
      return null;
    }

    const accessToken = entitlementsResponse.data.accessToken;
    const entitlement = entitlementsResponse.data.token || entitlementsResponse.data.entitlements_token;

    if (!accessToken || !entitlement) {
      emit({ type: 'error', error: 'Missing tokens from entitlements response', ts: Date.now() });
      return null;
    }

    // Now poll chat session to get puuid and region
    const chatData = await waitForChatSession(authHeader, baseUrl, 30000);
    if (chatData) {
      emit({ type: 'debug', message: `Got auth data: puuid=${chatData.puuid?.substring(0,8)}..., region=${chatData.region}`, ts: Date.now() });
      return {
        accessToken,
        entitlement,
        puuid: chatData.puuid,
        region: chatData.region
      };
    } else {
      emit({ type: 'error', error: 'Chat session did not become ready within timeout. Open Valorant to initialize chat.', ts: Date.now() });
      return null;
    }
  } catch (error) {
    emit({ type: 'error', error: `Failed to get local Riot auth: ${error.message}`, ts: Date.now() });
  }

  return null;
}

/**
 * Fetch PAS token (required for XMPP authentication)
 */
async function fetchPASToken(bearerToken, entitlementToken) {
  try {
    emit({ type: 'debug', message: 'Starting PAS token fetch with enhanced retry logic...', ts: Date.now() });

    const response = await retryRequest(async () => {
      // Add delay before each attempt to avoid rate limiting
      await new Promise(r => setTimeout(r, 500));

      return await httpsRequest('https://riot-geo.pas.si.riotgames.com/pas/v1/service/chat', {
        headers: {
          'Authorization': `Bearer ${bearerToken}`,
          'X-Riot-Entitlements-JWT': entitlementToken || '',
          'User-Agent': DEFAULT_UA,
          'Accept': 'application/json',
          'Content-Type': 'application/json'
        },
        timeout: 60000  // Increase timeout to 60 seconds
      });
    }, 5, 3000);  // 5 retries with 3 second base delay

    emit({ type: 'debug', message: `PAS token response status: ${response.statusCode}`, ts: Date.now() });

    if (response.statusCode !== 200) {
      throw new Error(`PAS token request failed with status ${response.statusCode}: ${JSON.stringify(response.data)}`);
    }

    if (response && response.data) {
      if (typeof response.data === 'string' && response.data.length > 10) {
        emit({ type: 'debug', message: 'PAS token received as string', ts: Date.now() });
        return response.data;
      }
      if (response.data.token && typeof response.data.token === 'string') {
        emit({ type: 'debug', message: 'PAS token received as object.token', ts: Date.now() });
        return response.data.token;
      }
      if (response.data.accessToken && typeof response.data.accessToken === 'string') {
        emit({ type: 'debug', message: 'PAS token received as object.accessToken', ts: Date.now() });
        return response.data.accessToken;
      }
    }

    emit({ type: 'error', error: `PAS token response unexpected. Type: ${typeof response.data}, Data: ${JSON.stringify(response.data)}`, ts: Date.now() });
    throw new Error('PAS token response unexpected');
  } catch (error) {
    emit({ type: 'error', error: `PAS token fetch error: ${error.message}`, code: error.code, ts: Date.now() });
    throw error;
  }
}

/**
 * Get entitlements token
 */
async function fetchEntitlementsToken(bearerToken) {
  const response = await retryRequest(async () => {
    return await httpsRequest('https://entitlements.auth.riotgames.com/api/token/v1', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${bearerToken}`,
        'Content-Type': 'application/json',
        'User-Agent': DEFAULT_UA
      },
      body: '{}',
      timeout: 60000
    });
  }, 5, 2000);

  if (response && response.data && (response.data.entitlements_token || response.data.token)) return response.data.entitlements_token || response.data.token;
  throw new Error('Entitlements response unexpected');
}

/**
 * Get Riot client config for XMPP server affinity
 */
async function getRiotConfig(token, entitlement) {
  const response = await retryRequest(async () => {
    return await httpsRequest('https://clientconfig.rpg.riotgames.com/api/v1/config/player?app=Riot%20Client', {
      headers: {
        'Authorization': `Bearer ${token}`,
        'X-Riot-Entitlements-JWT': entitlement,
        'User-Agent': DEFAULT_UA
      },
      timeout: 60000
    });
  }, 5, 2000);

  return response.data;
}

/**
 * Wait for socket to connect
 */
function waitForConnect(socket) {
  return new Promise((resolve, reject) => {
    // For TLS, wait for 'secureConnect' to ensure handshake is done
    if (!socket.connecting) {
      resolve();
      return;
    }
    const onSecureConnect = () => {
      socket.removeListener('error', onError);
      resolve();
    };
    const onError = (err) => {
      socket.removeListener('secureConnect', onSecureConnect);
      reject(err);
    };
    socket.once('secureConnect', onSecureConnect);
    socket.once('error', onError);
  });
}

/**
 * Write data to socket
 */
function asyncSocketWrite(socket, data) {
  return new Promise((resolve, reject) => {
    socket.write(data, (err) => {
      if (err) reject(err);
      else resolve();
    });
  });
}

/**
 * Read data from socket (waits for next data event)
 */
function asyncSocketRead(socket) {
  return new Promise((resolve) => {
    socket.once('data', (data) => {
      resolve(data.toString());
    });
  });
}

/**
 * NEW: Join a MUC room (party, pregame, in-game)
 */
async function joinMUCRoom(roomJid, nickname) {
  if (!xmppSocket || xmppSocket.destroyed) return;

  if (joinedRooms.has(roomJid)) {
    emit({ type: 'debug', message: `Already in room: ${roomJid}`, ts: Date.now() });
    return;
  }

  try {
    const presenceStanza = `<presence to="${roomJid}/${nickname}"><x xmlns="http://jabber.org/protocol/muc"/></presence>`;
    await asyncSocketWrite(xmppSocket, presenceStanza);
    joinedRooms.add(roomJid);
    emit({ type: 'info', message: `✅ Joined MUC room: ${roomJid}`, ts: Date.now() });
    console.error(`✅ Joined MUC room: ${roomJid}`);
  } catch (err) {
    emit({ type: 'error', error: `Failed to join room ${roomJid}: ${err.message}`, ts: Date.now() });
  }
}

/**
 * NEW: Extract room JID from presence/message stanza
 */
function extractRoomJid(dataStr) {
  const fromMatch = dataStr.match(/from=['"]([^'"]+)['"]/);
  if (!fromMatch) return null;

  const from = fromMatch[1];
  const roomJid = from.split('/')[0]; // Remove resource part

  // Debug: Log what we're checking
  console.error(`[DEBUG] Checking JID: ${roomJid}`);

  // Only return if it's a MUC room - check for all Valorant MUC room types
  // Rooms can be: ares-parties, ares-pregame, ares-coregame (team/party/all chat)
  if (roomJid.includes('@ares-parties') ||
      roomJid.includes('@ares-pregame') ||
      roomJid.includes('@ares-coregame')) {
    console.error(`[DEBUG] ✅ MUC ROOM DETECTED: ${roomJid}`);
    return roomJid;
  }

  return null;
}

/**
 * Connect to XMPP server with proper authentication
 */
async function connectXMPP() {
  if (isShuttingDown) return;

  try {
    emit({ type: 'info', message: 'Getting authentication credentials...', ts: Date.now() });

    const authData = await getLocalRiotAuth();
    if (!authData) {
      // Not an error we can fix without user action; retry later
      emit({ type: 'info', message: 'Auth not ready. Will retry in 10 seconds. Ensure Valorant is open.', ts: Date.now() });
      reconnectTimer = setTimeout(() => connectXMPP(), 10000);
      return;
    }

    const { accessToken, entitlement, puuid, region } = authData;
    currentPuuid = puuid; // Store for room joining

    emit({ type: 'info', message: 'Fetching PAS token...', ts: Date.now() });
    const pasToken = await fetchPASToken(accessToken, entitlement);

    emit({ type: 'info', message: 'Got entitlements token from local client', ts: Date.now() });

    let affinity = null;
    try {
      const pasParts = pasToken.split('.');
      if (pasParts.length === 3) {
        const pasData = JSON.parse(Buffer.from(pasParts[1], 'base64').toString('utf-8'));
        affinity = pasData['affinity'] || null;
      }
    } catch (_) { /* ignore parse errors */ }

    if (!affinity) {
      affinity = region || 'na';
      emit({ type: 'debug', message: `PAS token missing affinity; falling back to region=${affinity}`, ts: Date.now() });
    }

    emit({ type: 'info', message: `Affinity: ${affinity}`, ts: Date.now() });

    emit({ type: 'info', message: 'Fetching Riot config...', ts: Date.now() });
    const riotConfig = await getRiotConfig(accessToken, entitlement);

    if (!riotConfig['chat.affinities'] || !riotConfig['chat.affinities'][affinity]) {
      throw new Error(`Affinity ${affinity} not found in Riot config`);
    }
    if (!riotConfig['chat.affinity_domains'] || !riotConfig['chat.affinity_domains'][affinity]) {
      throw new Error(`Affinity domain for ${affinity} not found in Riot config`);
    }

    const affinityHost = riotConfig['chat.affinities'][affinity];
    const affinityDomain = riotConfig['chat.affinity_domains'][affinity];

    emit({ type: 'info', message: `Connecting to XMPP server: ${affinityHost}`, ts: Date.now() });

    // Establish TLS with SNI and reasonable timeouts
    const tlsOptions = {
      host: affinityHost,
      port: 5223,
      servername: affinityHost,
      // Leave cert validation on; we'll retry with off only if it fails
      rejectUnauthorized: true,
      timeout: 15000,
      keepAlive: true
    };

    let connectError = null;
    try {
      xmppSocket = tls.connect(tlsOptions);

      // Add error handler immediately to prevent unhandled errors
      xmppSocket.on('error', (err) => {
        emit({ type: 'error', error: `XMPP socket error during connection: ${err.message}`, code: err.code, ts: Date.now() });
        console.error('XMPP socket error:', err);
      });

      xmppSocket.on('close', () => {
        emit({ type: 'debug', message: 'XMPP socket closed during connection phase', ts: Date.now() });
      });

      await waitForConnect(xmppSocket);
    } catch (err) {
      connectError = err;
      emit({ type: 'debug', message: `TLS connect failed (${err.code || err.name}); retrying without cert validation`, ts: Date.now() });
      try {
        xmppSocket = tls.connect({ ...tlsOptions, rejectUnauthorized: false });

        // Add error handler for retry attempt too
        xmppSocket.on('error', (err) => {
          emit({ type: 'error', error: `XMPP socket error during retry: ${err.message}`, code: err.code, ts: Date.now() });
          console.error('XMPP socket error (retry):', err);
        });

        xmppSocket.on('close', () => {
          emit({ type: 'debug', message: 'XMPP socket closed during retry connection phase', ts: Date.now() });
        });

        await waitForConnect(xmppSocket);
      } catch (err2) {
        throw err2;
      }
    }

    emit({ type: 'info', message: 'Connected to XMPP server, authenticating...', ts: Date.now() });

    await asyncSocketWrite(xmppSocket, `<?xml version="1.0"?><stream:stream to="${affinityDomain}.pvp.net" version="1.0" xmlns="jabber:client" xmlns:stream="http://etherx.jabber.org/streams">`);
    let incomingData = '';
    do {
      incomingData = await asyncSocketRead(xmppSocket);
    } while (!incomingData.includes('X-Riot-RSO-PAS'));

    emit({ type: 'info', message: 'XMPP auth stage 2...', ts: Date.now() });
    await asyncSocketWrite(xmppSocket, `<auth mechanism="X-Riot-RSO-PAS" xmlns="urn:ietf:params:xml:ns:xmpp-sasl"><rso_token>${accessToken}</rso_token><pas_token>${pasToken}</pas_token></auth>`);
    await asyncSocketRead(xmppSocket);

    emit({ type: 'info', message: 'XMPP auth stage 3...', ts: Date.now() });
    await asyncSocketWrite(xmppSocket, `<?xml version="1.0"?><stream:stream to="${affinityDomain}.pvp.net" version="1.0" xmlns="jabber:client" xmlns:stream="http://etherx.jabber.org/streams">`);
    do {
      incomingData = await asyncSocketRead(xmppSocket);
    } while (!incomingData.includes('stream:features'));

    emit({ type: 'info', message: 'XMPP auth stage 4...', ts: Date.now() });
    await asyncSocketWrite(xmppSocket, '<iq id="_xmpp_bind1" type="set"><bind xmlns="urn:ietf:params:xml:ns:xmpp-bind"></bind></iq>');
    const bindResponse = await asyncSocketRead(xmppSocket);
    emit({ type: 'incoming', time: Date.now(), data: bindResponse });

    emit({ type: 'info', message: 'XMPP auth stage 5...', ts: Date.now() });
    await asyncSocketWrite(xmppSocket, '<iq id="_xmpp_session1" type="set"><session xmlns="urn:ietf:params:xml:ns:xmpp-session"/></iq>');
    await asyncSocketRead(xmppSocket);

    emit({ type: 'info', message: 'XMPP auth stage 6...', ts: Date.now() });
    await asyncSocketWrite(xmppSocket, `<iq id="xmpp_entitlements_0" type="set"><entitlements xmlns="urn:riotgames:entitlements"><token xmlns="">${entitlement}</token></entitlements></iq>`);
    await asyncSocketRead(xmppSocket);

    emit({ type: 'info', message: 'Connected to Riot XMPP server', ts: Date.now() });
    console.error('✅ Connected to Riot XMPP server');

    emit({ type: 'info', message: 'Requesting roster and chats...', ts: Date.now() });
    await asyncSocketWrite(xmppSocket, '<iq type="get" id="roster_1"><query xmlns="jabber:iq:riotgames:roster" last_state="true"/></iq>');
    await asyncSocketWrite(xmppSocket, '<iq type="get" id="recent_convos_2"><query xmlns="jabber:iq:riotgames:archive:list"/></iq>');

    emit({ type: 'info', message: 'Sending presence...', ts: Date.now() });
    await asyncSocketWrite(xmppSocket, '<presence/>');

    // Clear joined rooms on reconnect
    joinedRooms.clear();

    setupMessageHandlers();
    startKeepalive();
    startGameRoomMonitor(accessToken, authData);

  } catch (error) {
    emit({ type: 'error', error: `Connection failed: ${error.message}`, stack: error.stack, ts: Date.now() });
    console.error('Connection error:', error);

    if (!isShuttingDown) {
      emit({ type: 'info', message: 'Retrying connection in 10 seconds...', ts: Date.now() });
      reconnectTimer = setTimeout(() => connectXMPP(), 10000);
    }
  }
}

/**
 * Setup handlers for incoming XMPP messages
 */
function setupMessageHandlers() {
  if (!xmppSocket) return;

  xmppSocket.setTimeout(300000); // 5 min idle timeout
  xmppSocket.on('timeout', () => {
    emit({ type: 'info', message: 'XMPP idle timeout; closing and reconnecting', ts: Date.now() });
    try { xmppSocket.destroy(); } catch (_) {}
  });

  xmppSocket.on('data', (data) => {
    const dataStr = data.toString();

    emit({
      type: 'incoming',
      time: Date.now(),
      data: dataStr
    });

    // NEW: Auto-join MUC rooms when we see presence from them
    if (dataStr.includes('<presence')) {
      const roomJid = extractRoomJid(dataStr);
      if (roomJid && currentPuuid) {
        // Join the room automatically
        joinMUCRoom(roomJid, currentPuuid.substring(0, 8));
      }
      console.error('[PRESENCE] received');
    }

    if (dataStr.includes('<message')) {
      const fromMatch = dataStr.match(/from=['"]([^'\"]+)['"]/);
      const bodyMatch = dataStr.match(/<body>([^<]+)<\/body>/);
      if (fromMatch && bodyMatch) {
        console.error(`[MESSAGE] from ${fromMatch[1]}: ${bodyMatch[1].substring(0, 50)}...`);
      }
    } else if (dataStr.includes('<iq')) {
      console.error('[IQ] received');
    }
  });

  xmppSocket.on('error', (err) => {
    emit({ type: 'error', error: `XMPP socket error: ${err.message}`, ts: Date.now() });
    console.error('XMPP socket error:', err);
  });

  xmppSocket.on('close', () => {
    emit({ type: 'info', message: 'XMPP connection closed', ts: Date.now() });
    console.error('XMPP connection closed');

    if (heartbeatTimer) {
      clearInterval(heartbeatTimer);
      heartbeatTimer = null;
    }

    // Clear joined rooms on disconnect
    joinedRooms.clear();

    if (!isShuttingDown) {
      emit({ type: 'info', message: 'Reconnecting in 5 seconds...', ts: Date.now() });
      reconnectTimer = setTimeout(() => connectXMPP(), 5000);
    }
  });
}

/**
 * Monitor Valorant game state and join rooms proactively
 */
let gameRoomMonitorTimer = null;
let currentGameId = null;

async function startGameRoomMonitor(accessToken, authData) {
  if (gameRoomMonitorTimer) {
    clearInterval(gameRoomMonitorTimer);
  }

  const checkGameState = async () => {
    if (!xmppSocket || xmppSocket.destroyed || !currentPuuid) return;

    try {
      // Get lockfile to access local Riot API
      const lockfilePath = path.join(
        process.env.LOCALAPPDATA || process.env.APPDATA,
        'Riot Games/Riot Client/Config/lockfile'
      );

      if (!fs.existsSync(lockfilePath)) return;

      const lockfileData = fs.readFileSync(lockfilePath, 'utf8');
      const [name, pid, port, password, protocol] = lockfileData.split(':');
      const basicAuth = Buffer.from(`riot:${password}`).toString('base64');
      const baseUrl = `${protocol}://127.0.0.1:${port}`;

      // Check current game session
      const sessionResponse = await httpsRequest(`${baseUrl}/chat/v4/presences`, {
        headers: { 'Authorization': `Basic ${basicAuth}` },
        rejectUnauthorized: false,
        timeout: 5000
      });

      if (sessionResponse.statusCode === 200 && sessionResponse.data && sessionResponse.data.presences) {
        const selfPresence = sessionResponse.data.presences.find(p => p.puuid === currentPuuid);

        if (selfPresence && selfPresence.private) {
          const privateJson = Buffer.from(selfPresence.private, 'base64').toString('utf-8');
          let privateData = {};
          try { privateData = JSON.parse(privateJson); } catch (_) {}

          // Log loop state and candidate IDs for diagnostics
          const loop = privateData.sessionLoopState;
          const partyId = privateData.partyId || privateData.partyID || null;
          const pregameId = privateData.pregameId || privateData.preGameId || null;
          const coreGameId = privateData.matchId || privateData.coreGameId || privateData.gameId || null;
          const region = (authData.region || 'jp1');

          console.error(`[GAME] loop=${loop} partyId=${partyId || '-'} pregameId=${pregameId || '-'} coreGameId=${coreGameId || '-'} region=${region}`);

          // Priority 1: INGAME -> coregame MUC using coreGameId if available; fallback to partyId
          if (loop === 'INGAME') {
            const id = coreGameId || partyId;
            if (id) {
              const roomKey = `game:${id}`;
              if (roomKey !== currentGameId) {
                currentGameId = roomKey;
                const gameRoomJid = `${id}@ares-coregame.${region}.pvp.net`;
                emit({ type: 'info', message: `🎮 IN-GAME detected! Joining game room: ${gameRoomJid}`, ts: Date.now() });
                console.error(`🎮 IN-GAME! Joining: ${gameRoomJid}`);
                await joinMUCRoom(gameRoomJid, currentPuuid.substring(0, 8));
              }
            } else {
              console.error('[GAME] INGAME without coreGameId/partyId - cannot compute room JID');
            }
          }
          // Priority 2: PREGAME -> pregame MUC using pregameId if available; fallback to partyId
          else if (loop === 'PREGAME') {
            const id = pregameId || partyId;
            if (id) {
              const roomKey = `pregame:${id}`;
              if (roomKey !== currentGameId) {
                currentGameId = roomKey;
                const pregameRoomJid = `${id}@ares-pregame.${region}.pvp.net`;
                emit({ type: 'info', message: `⏳ PREGAME detected! Joining pregame room: ${pregameRoomJid}`, ts: Date.now() });
                console.error(`⏳ PREGAME! Joining: ${pregameRoomJid}`);
                await joinMUCRoom(pregameRoomJid, currentPuuid.substring(0, 8));
              }
            } else {
              console.error('[GAME] PREGAME without pregameId/partyId - cannot compute room JID');
            }
          }
          // Priority 3: MENUS but in a party -> parties MUC using partyId
          else if (partyId && loop === 'MENUS') {
            const roomKey = `party:${partyId}`;
            if (roomKey !== currentGameId) {
              currentGameId = roomKey;
              const partyRoomJid = `${partyId}@ares-parties.${region}.pvp.net`;
              emit({ type: 'info', message: `🎉 PARTY detected! Joining party room: ${partyRoomJid}`, ts: Date.now() });
              console.error(`🎉 PARTY! Joining: ${partyRoomJid}`);
              await joinMUCRoom(partyRoomJid, currentPuuid.substring(0, 8));
            }
          }
          // Not in game/party anymore
          else if (!partyId) {
            if (currentGameId) {
              emit({ type: 'info', message: '👋 Left game/party', ts: Date.now() });
              console.error('👋 Left game/party');
              currentGameId = null;
            }
          }
        } else {
          console.error('[GAME] Self presence missing or has no private payload');
        }
      } else {
        console.error(`[GAME] presences request failed: status=${sessionResponse.statusCode}`);
      }
    } catch (err) {
      // Silently ignore errors - this is a background monitor
      if (err.code !== 'ECONNREFUSED' && err.code !== 'ETIMEDOUT') {
        emit({ type: 'debug', message: `Game monitor error: ${err.message}`, ts: Date.now() });
        console.error('[GAME] monitor error:', err.message);
      }
    }
  };

  // Check immediately, then every 5 seconds
  checkGameState();
  gameRoomMonitorTimer = setInterval(checkGameState, 5000);

  emit({ type: 'info', message: 'Game room monitor started (checking every 5s)', ts: Date.now() });
  console.error('🔍 Game room monitor started');
}

/**
 * Start keepalive timer
 */
function startKeepalive() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer);
  }

  heartbeatTimer = setInterval(() => {
    emit({ type: 'heartbeat', ts: Date.now() });

    if (xmppSocket && !xmppSocket.destroyed) {
      asyncSocketWrite(xmppSocket, ' ').catch(err => {
        console.error('Keepalive error:', err);
      });
    }
  }, 150000);
}

/**
 * Graceful shutdown
 */
function shutdown(reason) {
  if (isShuttingDown) return;
  isShuttingDown = true;

  console.error(`Shutting down: ${reason}`);

  if (reconnectTimer) clearTimeout(reconnectTimer);
  if (heartbeatTimer) clearInterval(heartbeatTimer);
  if (gameRoomMonitorTimer) clearInterval(gameRoomMonitorTimer);

  if (xmppSocket && !xmppSocket.destroyed) {
    xmppSocket.destroy();
  }

  emit({ type: 'shutdown', reason, ts: Date.now() });

  setTimeout(() => {
    process.exit(0);
  }, 1000);
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('uncaughtException', (err) => {
  emit({ type: 'error', error: err.message, stack: err.stack, ts: Date.now() });
  console.error('Uncaught exception:', err);
  shutdown('uncaughtException');
});

process.on('unhandledRejection', (reason, promise) => {
  emit({ type: 'error', error: `Unhandled rejection: ${reason}`, ts: Date.now() });
  console.error('Unhandled rejection:', reason);
});

connectXMPP();
