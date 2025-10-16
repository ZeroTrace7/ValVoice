#!/usr/bin/env node
/**
 * ValVoice XMPP Bridge - Real Riot XMPP Client
 * Based on valorant-xmpp-watcher implementation
 * Connects to Riot Games XMPP service for Valorant chat
 */

const tls = require('tls');
const fs = require('fs');
const path = require('path');
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

emit({ type: 'startup', pid: process.pid, ts: Date.now(), version: '2.0.0-real' });

/**
 * Make HTTPS request using native module
 */
function httpsRequest(url, options = {}) {
  return new Promise((resolve, reject) => {
    const urlObj = new URL(url);
    const reqOptions = {
      hostname: urlObj.hostname,
      port: urlObj.port || 443,
      path: urlObj.pathname + urlObj.search,
      method: options.method || 'GET',
      headers: options.headers || {},
      rejectUnauthorized: options.rejectUnauthorized !== false
    };

    const req = https.request(reqOptions, (res) => {
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => {
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

    req.on('error', reject);
    req.setTimeout(10000, () => {
      req.destroy();
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

    const auth = Buffer.from(`riot:${password}`).toString('base64');

    // First, get the entitlements token which contains the proper access token
    const entitlementsResponse = await httpsRequest(`${protocol}://127.0.0.1:${port}/entitlements/v1/token`, {
      headers: { 'Authorization': `Basic ${auth}` },
      rejectUnauthorized: false
    });

    emit({ type: 'debug', message: `Entitlements response: status=${entitlementsResponse.statusCode}`, ts: Date.now() });

    if (entitlementsResponse.statusCode !== 200 || !entitlementsResponse.data) {
      emit({ type: 'error', error: `Failed to get entitlements. Status: ${entitlementsResponse.statusCode}`, ts: Date.now() });
      return null;
    }

    const accessToken = entitlementsResponse.data.accessToken;
    const entitlement = entitlementsResponse.data.token;

    // Now get the chat session to get puuid and region
    const chatResponse = await httpsRequest(`${protocol}://127.0.0.1:${port}/chat/v1/session`, {
      headers: { 'Authorization': `Basic ${auth}` },
      rejectUnauthorized: false
    });

    emit({ type: 'debug', message: `Chat session response: status=${chatResponse.statusCode}, loaded=${chatResponse.data?.loaded}`, ts: Date.now() });

    if (chatResponse.statusCode === 200 && chatResponse.data && chatResponse.data.loaded) {
      emit({ type: 'debug', message: `Got auth data: puuid=${chatResponse.data.puuid?.substring(0,8)}..., region=${chatResponse.data.region}`, ts: Date.now() });
      return {
        accessToken: accessToken,
        entitlement: entitlement,
        puuid: chatResponse.data.puuid,
        region: chatResponse.data.region
      };
    } else {
      emit({ type: 'error', error: `Chat session not ready. Status: ${chatResponse.statusCode}, loaded: ${chatResponse.data?.loaded}`, ts: Date.now() });
    }
  } catch (error) {
    emit({ type: 'error', error: `Failed to get local Riot auth: ${error.message}`, ts: Date.now() });
  }

  return null;
}

/**
 * Fetch PAS token (required for XMPP authentication)
 */
async function fetchPASToken(bearerToken) {
  try {
    const response = await httpsRequest('https://riot-geo.pas.si.riotgames.com/pas/v1/service/chat', {
      headers: {
        'Authorization': `Bearer ${bearerToken}`,
        'User-Agent': ''
      }
    });

    emit({ type: 'debug', message: `PAS token response status: ${response.statusCode}`, ts: Date.now() });

    // Check for error status codes
    if (response.statusCode !== 200) {
      throw new Error(`PAS token request failed with status ${response.statusCode}: ${JSON.stringify(response.data)}`);
    }

    // Some deployments return a raw string, others return { token: '...' }
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

    // Log the actual response for debugging
    emit({ type: 'error', error: `PAS token response unexpected. Type: ${typeof response.data}, Data: ${JSON.stringify(response.data)}`, ts: Date.now() });
    throw new Error('PAS token response unexpected');
  } catch (error) {
    emit({ type: 'error', error: `PAS token fetch error: ${error.message}`, ts: Date.now() });
    throw error;
  }
}

/**
 * Get entitlements token
 */
async function fetchEntitlementsToken(bearerToken) {
  const response = await httpsRequest('https://entitlements.auth.riotgames.com/api/token/v1', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${bearerToken}`,
      'Content-Type': 'application/json',
      'User-Agent': ''
    },
    body: '{}'
  });
  if (response && response.data && response.data.entitlements_token) return response.data.entitlements_token;
  throw new Error('Entitlements response unexpected');
}

/**
 * Get Riot client config for XMPP server affinity
 */
async function getRiotConfig(token, entitlement) {
  const response = await httpsRequest('https://clientconfig.rpg.riotgames.com/api/v1/config/player?app=Riot%20Client', {
    headers: {
      'Authorization': `Bearer ${token}`,
      'X-Riot-Entitlements-JWT': entitlement,
      'User-Agent': ''
    }
  });
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
 * Connect to XMPP server with proper authentication
 */
async function connectXMPP() {
  if (isShuttingDown) return;

  try {
    emit({ type: 'info', message: 'Getting authentication credentials...', ts: Date.now() });

    const authData = await getLocalRiotAuth();
    if (!authData) {
      throw new Error('Could not authenticate with Riot Client. Make sure Valorant/Riot Client is running.');
    }

    const { accessToken, entitlement, puuid } = authData;

    emit({ type: 'info', message: 'Fetching PAS token...', ts: Date.now() });
    const pasToken = await fetchPASToken(accessToken);

    emit({ type: 'info', message: 'Got entitlements token from local client', ts: Date.now() });

    const pasParts = pasToken.split('.');
    if (pasParts.length !== 3) throw new Error('Invalid PAS token format');
    const pasData = JSON.parse(Buffer.from(pasParts[1], 'base64').toString('utf-8'));
    const affinity = pasData['affinity'];
    if (!affinity) throw new Error('Missing affinity in PAS token');

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

    xmppSocket = tls.connect({
      host: affinityHost,
      port: 5223
    });

    await waitForConnect(xmppSocket);

    emit({ type: 'info', message: 'Connected to XMPP server, authenticating...', ts: Date.now() });

    await asyncSocketWrite(xmppSocket, `<?xml version="1.0"?><stream:stream to="${affinityDomain}.pvp.net" version="1.0" xmlns:stream="http://etherx.jabber.org/streams">`);
    let incomingData = '';
    do {
      incomingData = await asyncSocketRead(xmppSocket);
    } while (!incomingData.includes('X-Riot-RSO-PAS'));

    emit({ type: 'info', message: 'XMPP auth stage 2...', ts: Date.now() });
    await asyncSocketWrite(xmppSocket, `<auth mechanism="X-Riot-RSO-PAS" xmlns="urn:ietf:params:xml:ns:xmpp-sasl"><rso_token>${accessToken}</rso_token><pas_token>${pasToken}</pas_token></auth>`);
    await asyncSocketRead(xmppSocket);

    emit({ type: 'info', message: 'XMPP auth stage 3...', ts: Date.now() });
    await asyncSocketWrite(xmppSocket, `<?xml version="1.0"?><stream:stream to="${affinityDomain}.pvp.net" version="1.0" xmlns:stream="http://etherx.jabber.org/streams">`);
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

    setupMessageHandlers();
    startKeepalive();

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

  xmppSocket.on('data', (data) => {
    const dataStr = data.toString();

    emit({
      type: 'incoming',
      time: Date.now(),
      data: dataStr
    });

    if (dataStr.includes('<message')) {
      const fromMatch = dataStr.match(/from=['"]([^'"]+)['"]/);
      const bodyMatch = dataStr.match(/<body>([^<]+)<\/body>/);
      if (fromMatch && bodyMatch) {
        console.error(`[MESSAGE] from ${fromMatch[1]}: ${bodyMatch[1].substring(0, 50)}...`);
      }
    } else if (dataStr.includes('<presence')) {
      console.error('[PRESENCE] received');
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

    if (!isShuttingDown) {
      emit({ type: 'info', message: 'Reconnecting in 5 seconds...', ts: Date.now() });
      reconnectTimer = setTimeout(() => connectXMPP(), 5000);
    }
  });
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
