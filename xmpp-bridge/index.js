#!/usr/bin/env node
/**
 * ValVoice XMPP Bridge - Direct Connection Mode (Default)
 *
 * ARCHITECTURE: Standalone Parallel XMPP Client
 *
 * This script connects DIRECTLY to Riot's XMPP server as a second client
 * (parallel to the Valorant game client). It does NOT intercept traffic.
 *
 * Message Reception:
 *   - PARTY/TEAM/ALL: Received via MUC room membership (we join the same rooms as Valorant)
 *   - WHISPER: Received directly from Riot's server to our XMPP session
 *   - OWN MESSAGES: Received via XMPP Carbons (XEP-0280) for party/team, or direct for whispers
 *
 * Optional MITM Proxy Mode:
 *   Use --mitm flag or VALVOICE_MITM=true to intercept Valorant's traffic.
 *   This requires modifying the hosts file and is NOT the default mode.
 */

const tls = require('tls');
const net = require('net');
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

// MITM Proxy state
let proxyServer = null; // TCP server that Valorant connects to
let clientSocket = null; // Connection from Valorant client
let serverSocket = null; // Connection to Riot's XMPP server
let isShuttingDown = false;

// Backwards compatibility: keep existing variables
let xmppSocket = null;
let reconnectTimer = null;
let heartbeatTimer = null;

// Track joined MUC rooms to avoid duplicate joins
const joinedRooms = new Set();
let currentPuuid = null; // Store puuid for room joining
let currentRegion = null; // Store region for room JID construction
let currentRoomJid = null; // Current active room for sending messages
let currentGameId = null; // Current game/party/pregame state key (e.g., "game:uuid", "party:uuid")
let lastExtractedGameState = null; // Last state key from presence extraction

emit({ type: 'startup', pid: process.pid, ts: Date.now(), version: '3.5.0-e2e-verified' });

// Default UA used for Riot endpoints that sometimes reject empty UA
const DEFAULT_UA = 'ValVoice-XMPP/2.3 (Windows; Node.js)';

// Disable global keep-alive to prevent connection reuse issues
http.globalAgent.keepAlive = false;
https.globalAgent.keepAlive = false;

// ============ STDIN COMMAND HANDLER ============
// Receives JSON commands from Java process for sending messages
process.stdin.setEncoding('utf8');
process.stdin.on('data', (data) => {
  try {
    const lines = data.trim().split('\n');
    for (const line of lines) {
      if (!line.trim()) continue;
      const cmd = JSON.parse(line);
      handleCommand(cmd);
    }
  } catch (e) {
    emit({ type: 'error', error: `Command parse error: ${e.message}`, ts: Date.now() });
  }
});

/**
 * Handle incoming command from Java
 */
function handleCommand(cmd) {
  if (!cmd || !cmd.type) return;

  switch (cmd.type) {
    case 'send':
      if (cmd.to && cmd.body) {
        sendXmppMessage(cmd.to, cmd.body, cmd.msgType || 'groupchat');
      }
      break;
    case 'join':
      if (cmd.room) {
        joinMUCRoom(cmd.room, currentPuuid ? currentPuuid.substring(0, 8) : 'valvoice');
      }
      break;
    case 'leave':
      if (cmd.room) {
        leaveMUCRoomIfJoined(cmd.room);
      }
      break;
    default:
      emit({ type: 'debug', message: `Unknown command type: ${cmd.type}`, ts: Date.now() });
  }
}

/**
 * Send an XMPP message to a JID
 */
async function sendXmppMessage(to, body, msgType = 'groupchat') {
  if (!xmppSocket || xmppSocket.destroyed) {
    emit({ type: 'error', error: 'Cannot send: not connected', ts: Date.now() });
    return false;
  }

  try {
    const escapedBody = escapeXml(body);
    const msgStanza = `<message to="${to}" type="${msgType}"><body>${escapedBody}</body></message>`;
    await asyncSocketWrite(xmppSocket, msgStanza);
    emit({ type: 'outgoing', data: msgStanza, ts: Date.now() });
    emit({ type: 'info', message: `📤 Sent message to ${to.split('@')[0].substring(0, 8)}...`, ts: Date.now() });

    // ✨ CAPTURE OWN MESSAGE FOR TTS ✨
    // Determine message type from 'to' JID
    let messageCategory = 'WHISPER';
    let syntheticFrom = '';

    // For MUC messages (party/team), the 'from' should be the room JID with your nickname
    if (to.includes('@ares-parties') || to.includes('@ares-pregame') || to.includes('@ares-coregame')) {
      // MUC message - use room JID with your PUUID as nickname
      const nickname = currentPuuid ? currentPuuid.substring(0, 8) : 'self';
      syntheticFrom = `${to}/${currentPuuid || nickname}`;

      if (to.includes('@ares-parties')) {
        messageCategory = 'PARTY';
      } else if (to.includes('@ares-pregame')) {
        messageCategory = 'TEAM';
      } else if (to.includes('all@ares-coregame')) {
        messageCategory = 'ALL';
      } else {
        messageCategory = 'TEAM';
      }
    } else {
      // Whisper - use your user JID
      syntheticFrom = currentPuuid ? `${currentPuuid}@${currentRegion || 'jp1'}.pvp.net` : to;
      messageCategory = 'WHISPER';
    }

    // Create a synthetic message XML that looks like it came from you
    const syntheticXml = `<message from='${syntheticFrom}' to='${to}' type='${msgType}'><body>${escapedBody}</body></message>`;

    // Send to Java as if it was an incoming message (for TTS processing)
    emit({
      type: 'incoming',
      time: Date.now(),
      data: syntheticXml
    });

    emit({
      type: 'info',
      message: `🔊 [${messageCategory}] Your message queued for TTS: "${body.substring(0, 30)}${body.length > 30 ? '...' : ''}"`,
      ts: Date.now()
    });

    return true;
  } catch (err) {
    emit({ type: 'error', error: `Send failed: ${err.message}`, ts: Date.now() });
    return false;
  }
}

/**
 * Escape special XML characters
 */
function escapeXml(str) {
  if (!str) return '';
  return str.replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&apos;');
}

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

// ============ MITM PROXY IMPLEMENTATION ============

/**
 * Start MITM proxy server
 * Valorant will connect to this instead of directly to Riot's servers
 * We forward traffic and intercept messages in both directions
 */
async function startMITMProxy() {
  const PROXY_PORT = 5223; // Same port as Riot's XMPP server
  const PROXY_HOST = '0.0.0.0'; // Listen on all interfaces

  // Note: For this to work, you need to redirect Valorant's DNS or use hosts file:
  // Add to C:\Windows\System32\drivers\etc\hosts:
  // 127.0.0.1 jp1.chat.si.riotgames.com
  // 127.0.0.1 ap1.chat.si.riotgames.com
  // 127.0.0.1 na2.chat.si.riotgames.com
  // etc. for all regions
  
  // We need TLS certificates to act as a proper MITM
  // For simplicity, we'll use a transparent TCP proxy instead
  // This means we need to use iptables/WinDivert for true MITM

  proxyServer = net.createServer((client) => {
    clientSocket = client;
    emit({ type: 'info', message: '🔌 Valorant client connected to MITM proxy', ts: Date.now() });
    
    let buffer = Buffer.alloc(0);
    let serverHost = null;
    let hasConnectedToServer = false;

    const onData = (data) => {
      buffer = Buffer.concat([buffer, data]);

      // Try to detect target server
      if (!hasConnectedToServer) {
        const bufferStr = buffer.toString('utf8', 0, Math.min(buffer.length, 1024));

        // Look for SNI (Server Name Indication) in TLS handshake
        // Or look for XMPP stream header
        const streamMatch = bufferStr.match(/to=['"]([^'"]+)['"]/);
        if (streamMatch) {
          serverHost = streamMatch[1];
          if (!serverHost.includes('.')) {
            serverHost = serverHost + '.pvp.net';
          }

          // Extract region
          const regionMatch = serverHost.match(/^([^.]+)\./);
          if (regionMatch) {
            currentRegion = regionMatch[1];
          }

          hasConnectedToServer = true;
          client.removeListener('data', onData);
          connectToRiotServer(client, serverHost, 5223, buffer);
        } else if (buffer.length > 2000) {
          // Fallback: assume ap1 region if can't detect
          serverHost = (currentRegion || 'ap1') + '.chat.si.riotgames.com';
          hasConnectedToServer = true;
          client.removeListener('data', onData);
          connectToRiotServer(client, serverHost, 5223, buffer);
        }
      }
    };
    
    client.on('data', onData);

    client.on('error', (err) => {
      emit({ type: 'error', error: `Client socket error: ${err.message}`, ts: Date.now() });
    });
    
    client.on('close', () => {
      emit({ type: 'info', message: '👋 Valorant client disconnected', ts: Date.now() });
      if (serverSocket) {
        serverSocket.end();
        serverSocket = null;
      }
    });
  });
  
  proxyServer.listen(PROXY_PORT, PROXY_HOST, () => {
    emit({ type: 'info', message: `🚀 MITM Proxy listening on ${PROXY_HOST}:${PROXY_PORT}`, ts: Date.now() });
    emit({ type: 'info', message: '⚠️  Make sure to add Riot XMPP servers to your hosts file pointing to 127.0.0.1', ts: Date.now() });
  });
  
  proxyServer.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
      emit({ type: 'error', error: `Port ${PROXY_PORT} is already in use. Is Valorant running? Try closing it first.`, ts: Date.now() });
    } else {
      emit({ type: 'error', error: `Proxy server error: ${err.message}`, ts: Date.now() });
    }
  });
}

/**
 * Connect to Riot's actual XMPP server and set up bidirectional forwarding
 */
function connectToRiotServer(client, serverHost, serverPort, initialData) {
  emit({ type: 'info', message: `🔗 Connecting to Riot server: ${serverHost}:${serverPort}`, ts: Date.now() });
  
  // Connect to real Riot XMPP server
  serverSocket = tls.connect({
    host: serverHost,
    port: serverPort,
    rejectUnauthorized: false
  }, () => {
    emit({ type: 'info', message: `✅ Connected to Riot XMPP server`, ts: Date.now() });
    
    // Send buffered initial data to server
    if (initialData.length > 0) {
      serverSocket.write(initialData);
    }
  });
  
  // Forward client -> server (OUTGOING from Valorant)
  client.on('data', (data) => {
    if (serverSocket && !serverSocket.destroyed) {
      serverSocket.write(data);
      
      // INTERCEPT OUTGOING MESSAGES (YOUR messages)
      const dataStr = data.toString();
      if (dataStr.includes('<message') && dataStr.includes('<body>')) {
        handleOutgoingMessage(dataStr);
      }
    }
  });
  
  // Forward server -> client (INCOMING to Valorant)
  serverSocket.on('data', (data) => {
    if (client && !client.destroyed) {
      client.write(data);
      
      // Process incoming messages as before
      const dataStr = data.toString();
      emit({
        type: 'incoming',
        time: Date.now(),
        data: dataStr
      });
      
      // Parse incoming messages
      if (dataStr.includes('<message') && dataStr.includes('<body>')) {
        handleIncomingMessage(dataStr);
      }
      
      // Detect self ID
      if (dataStr.includes('<iq') && dataStr.includes('_xmpp_bind1')) {
        const jidMatch = dataStr.match(/<jid>([^<]+)<\/jid>/);
        if (jidMatch && jidMatch[1].includes('@')) {
          const selfId = jidMatch[1].split('@')[0];
          currentPuuid = selfId;
          emit({ type: 'info', message: `Self ID detected: ${selfId}`, ts: Date.now() });
        }
      }
      
      // Auto-join rooms
      if (dataStr.includes('<presence') && currentPuuid) {
        const roomJid = extractRoomJid(dataStr);
        if (roomJid) {
          joinMUCRoom(roomJid, currentPuuid.substring(0, 8));
        }
      }
    }
  });
  
  serverSocket.on('error', (err) => {
    emit({ type: 'error', error: `Server socket error: ${err.message}`, ts: Date.now() });
    client.end();
  });
  
  serverSocket.on('close', () => {
    emit({ type: 'info', message: '🔌 Riot server connection closed', ts: Date.now() });
    client.end();
  });
}

/**
 * Handle outgoing message from Valorant (YOUR message)
 */
function handleOutgoingMessage(xml) {
  try {
    const toMatch = xml.match(/to=['"]([^'"]+)['"]/);
    const typeMatch = xml.match(/type=['"]([^'"]+)['"]/);
    const bodyMatch = xml.match(/<body>([\s\S]*?)<\/body>/);
    
    if (!toMatch || !bodyMatch) return;
    
    const to = toMatch[1];
    const msgType = typeMatch ? typeMatch[1] : 'unknown';
    const body = bodyMatch[1];
    
    // Determine message category
    let roomType = 'WHISPER';
    if (to.includes('@ares-parties')) roomType = 'PARTY';
    else if (to.includes('@ares-pregame')) roomType = 'PREGAME';
    else if (to.includes('all@ares-coregame')) roomType = 'ALL';
    else if (to.includes('@ares-coregame')) roomType = 'TEAM';
    
    emit({
      type: 'info',
      message: `📤 [${roomType}] YOU: ${body.substring(0, 50)}${body.length > 50 ? '...' : ''}`,
      ts: Date.now()
    });
    
    // Create synthetic incoming message for TTS
    // Make it look like it came from you
    const syntheticFrom = currentPuuid ? 
      (msgType === 'groupchat' ? `${to}/${currentPuuid}` : `${currentPuuid}@${currentRegion || 'jp1'}.pvp.net`) :
      to;
    
    const syntheticXml = `<message from='${syntheticFrom}' to='${to}' type='${msgType}'><body>${body}</body></message>`;
    
    // Send to Java for TTS processing
    emit({
      type: 'incoming',
      time: Date.now(),
      data: syntheticXml
    });
    
    emit({
      type: 'outgoing-captured',
      roomType: roomType,
      body: body,
      ts: Date.now()
    });
    
  } catch (err) {
    emit({ type: 'error', error: `Failed to handle outgoing message: ${err.message}`, ts: Date.now() });
  }
}

/**
 * Handle incoming message to Valorant
 */
function handleIncomingMessage(xml) {
  try {
    const fromMatch = xml.match(/from=['"]([^'"]+)['"]/);
    const typeMatch = xml.match(/type=['"]([^'"]+)['"]/);
    const bodyMatch = xml.match(/<body>([\s\S]*?)<\/body>/);
    
    if (!fromMatch || !bodyMatch) return;
    
    const from = fromMatch[1];
    const msgType = typeMatch ? typeMatch[1] : 'unknown';
    const body = bodyMatch[1];
    
    // Determine message category
    let roomType = 'WHISPER';
    if (from.includes('@ares-parties')) roomType = 'PARTY';
    else if (from.includes('@ares-pregame')) roomType = 'PREGAME';
    else if (from.includes('all@ares-coregame')) roomType = 'ALL';
    else if (from.includes('@ares-coregame')) roomType = 'TEAM';
    
    const fromShort = from.split('/')[1] || from.split('@')[0].substring(0, 8);
    
    emit({
      type: 'info',
      message: `💬 [${roomType}][${msgType}] ${fromShort}: ${body.substring(0, 50)}${body.length > 50 ? '...' : ''}`,
      ts: Date.now()
    });
    
    emit({
      type: 'message-detected',
      roomType: roomType,
      messageType: msgType,
      from: from,
      isMuc: from.includes('@ares-'),
      ts: Date.now()
    });
    
  } catch (err) {
    // Silent - non-critical
  }
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
        // Only log on final retry attempts to reduce spam
        if (attempt >= 3) {
          emit({ type: 'debug', message: `Request retry ${attempt}/${maxRetries}`, ts: Date.now() });
        }
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

    const basicAuth = Buffer.from(`riot:${password}`).toString('base64');
    const baseUrl = `${protocol}://127.0.0.1:${port}`;
    const authHeader = `Basic ${basicAuth}`;

    // First, get the entitlements token which contains the proper access token
    const entitlementsResponse = await httpsRequest(`${baseUrl}/entitlements/v1/token`, {
      headers: { 'Authorization': authHeader },
      rejectUnauthorized: false
    });


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

    if (response.statusCode !== 200) {
      throw new Error(`PAS token request failed with status ${response.statusCode}`);
    }

    if (response && response.data) {
      if (typeof response.data === 'string' && response.data.length > 10) {
        return response.data;
      }
      if (response.data.token && typeof response.data.token === 'string') {
        return response.data.token;
      }
      if (response.data.accessToken && typeof response.data.accessToken === 'string') {
        return response.data.accessToken;
      }
    }

    emit({ type: 'error', error: 'PAS token response unexpected', ts: Date.now() });
    throw new Error('PAS token response unexpected');
  } catch (error) {
    emit({ type: 'error', error: `PAS token fetch error: ${error.message}`, ts: Date.now() });
    throw error;
  }
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
 * Enable XMPP Message Carbons (XEP-0280)
 * Carbons allow receiving copies of messages sent TO and FROM other resources.
 * This is CRITICAL for receiving party/team messages that may be sent to/from
 * the game client's XMPP resource.
 *
 * Must be called AFTER handlers are registered but BEFORE sending presence.
 */
async function enableCarbons() {
  if (!xmppSocket || xmppSocket.destroyed) {
    emit({ type: 'warn', message: 'Cannot enable carbons: socket not available', ts: Date.now() });
    return false;
  }

  try {
    // XEP-0280: Enable carbons
    const carbonEnableStanza = '<iq id="enable_carbons_1" type="set"><enable xmlns="urn:xmpp:carbons:2"/></iq>';

    emit({ type: 'debug', message: 'Sending carbons enable request...', ts: Date.now() });
    await asyncSocketWrite(xmppSocket, carbonEnableStanza);

    // Wait for response (with timeout)
    const response = await Promise.race([
      asyncSocketRead(xmppSocket),
      new Promise((_, reject) => setTimeout(() => reject(new Error('Carbons enable timeout')), 5000))
    ]);

    // Check if carbons were enabled successfully
    if (response.includes('enable_carbons_1') && response.includes('type="result"')) {
      emit({ type: 'info', message: '✅ Carbons enabled', ts: Date.now() });
      return true;
    } else if (response.includes('enable_carbons_1') && response.includes('type="error"')) {
      // Carbons may not be supported or already enabled - not fatal
      emit({ type: 'warn', message: '⚠️ Carbons enable returned error (may already be enabled)', ts: Date.now() });
      return true; // Continue anyway
    } else {
      // Got a different response - emit it for processing and continue
      emit({ type: 'incoming', time: Date.now(), data: response });
      emit({ type: 'info', message: '✅ Carbons enabled (response deferred)', ts: Date.now() });
      return true;
    }
  } catch (err) {
    emit({ type: 'warn', message: `Carbons enable failed: ${err.message} (continuing anyway)`, ts: Date.now() });
    return false; // Non-fatal - continue with connection
  }
}

/**
 * Join a MUC room (party, pregame, in-game) with retry logic
 *
 * Room JID formats:
 *   Party:   <partyId>@ares-parties.<region>.pvp.net
 *   Pregame: <matchId>@ares-pregame.<region>.pvp.net
 *   Team:    <matchId>@ares-coregame.<region>.pvp.net
 *   All:     <matchId>all@ares-coregame.<region>.pvp.net
 */
async function joinMUCRoom(roomJid, nickname, retryCount = 0) {
  // Determine room type for logging
  const roomType = roomJid.includes('@ares-parties') ? 'PARTY' :
                   roomJid.includes('@ares-pregame') ? 'PREGAME' :
                   roomJid.includes('all@ares-coregame') ? 'ALL' :
                   roomJid.includes('@ares-coregame') ? 'TEAM' : 'UNKNOWN';

  const roomIdShort = roomJid.split('@')[0].substring(0, 12) + '...';

  // Log room join attempt
  emit({ type: 'info', message: `🚪 [${roomType}] Room join attempt: ${roomIdShort}`, ts: Date.now() });
  emit({ type: 'debug', message: `   Full JID: ${roomJid}`, ts: Date.now() });
  emit({ type: 'debug', message: `   Current joinedRooms (${joinedRooms.size}): [${Array.from(joinedRooms).map(r => r.split('@')[0].substring(0, 8) + '...').join(', ')}]`, ts: Date.now() });

  if (!xmppSocket || xmppSocket.destroyed) {
    emit({ type: 'warn', message: `❌ [${roomType}] Cannot join: socket not available`, ts: Date.now() });
    return false;
  }

  // CRITICAL: Ensure message handlers are registered before joining
  // This prevents the race condition where groupchat messages arrive before listener is active
  if (!messageHandlersSetup) {
    emit({ type: 'warn', message: `❌ [${roomType}] Cannot join: message handlers not yet registered`, ts: Date.now() });
    return false;
  }

  // Check for duplicate join - but NEVER block the first join for this room
  if (joinedRooms.has(roomJid)) {
    emit({ type: 'debug', message: `⏭️ [${roomType}] Already in room, skipping duplicate join`, ts: Date.now() });
    return true;
  }

  try {
    // Use full puuid as nickname for uniqueness, or fallback
    const nick = nickname || (currentPuuid ? currentPuuid.substring(0, 8) : 'valvoice');

    // MUC presence stanza with history request
    const presenceStanza = `<presence to="${roomJid}/${nick}">` +
      `<x xmlns="http://jabber.org/protocol/muc">` +
      `<history maxstanzas="50" seconds="300"/>` +
      `</x>` +
      `</presence>`;

    emit({ type: 'debug', message: `   Sending MUC presence for ${roomType}`, ts: Date.now() });

    await asyncSocketWrite(xmppSocket, presenceStanza);

    // Add to joined rooms immediately after sending presence
    joinedRooms.add(roomJid);

    // Track current room for sending messages
    currentRoomJid = roomJid;

    // Emit room-joined event so Java can track the current room
    emit({ type: 'room-joined', room: roomJid, roomType: roomType, ts: Date.now() });

    // Log join success with current state
    emit({ type: 'info', message: `✅ [${roomType}] Join success: ${roomIdShort}`, ts: Date.now() });
    emit({ type: 'info', message: `📋 joinedRooms state (${joinedRooms.size}): [${Array.from(joinedRooms).map(r => {
      const id = r.split('@')[0].substring(0, 8);
      const type = r.includes('@ares-parties') ? 'P' : r.includes('@ares-pregame') ? 'PRE' : r.includes('all@ares-coregame') ? 'ALL' : r.includes('@ares-coregame') ? 'T' : '?';
      return `${type}:${id}...`;
    }).join(', ')}]`, ts: Date.now() });

    return true;
  } catch (err) {
    // Retry up to 3 times with exponential backoff
    if (retryCount < 3) {
      const delay = 1000 * Math.pow(2, retryCount);
      emit({ type: 'debug', message: `🔄 [${roomType}] Retry join (${retryCount + 1}/3) after ${delay}ms`, ts: Date.now() });
      await new Promise(r => setTimeout(r, delay));
      return joinMUCRoom(roomJid, nickname, retryCount + 1);
    }
    emit({ type: 'error', error: `❌ [${roomType}] Failed to join room after retries: ${err.message}`, ts: Date.now() });
    return false;
  }
}

/**
 * Extract room JID from presence/message stanza
 */
function extractRoomJid(dataStr) {
  const fromMatch = dataStr.match(/from=['"]([^'"]+)['"]/);
  if (!fromMatch) return null;

  const from = fromMatch[1];
  const roomJid = from.split('/')[0]; // Remove resource part

  // Only return if it's a MUC room - check for all Valorant MUC room types
  if (roomJid.includes('@ares-parties') ||
      roomJid.includes('@ares-pregame') ||
      roomJid.includes('@ares-coregame')) {
    return roomJid;
  }

  return null;
}

/**
 * Construct a party room JID
 * Format: <partyId>@ares-parties.<region>.pvp.net
 * @param {string} partyId - The party UUID
 * @param {string} region - The region code (e.g., 'jp1', 'ap', 'na1')
 * @returns {string} The full room JID
 */
function constructPartyRoomJid(partyId, region) {
  const r = region || currentRegion || 'ap';
  const jid = `${partyId}@ares-parties.${r}.pvp.net`;
  emit({ type: 'debug', message: `Constructed PARTY JID: ${jid}`, ts: Date.now() });
  return jid;
}

/**
 * Construct a pregame room JID
 * Format: <matchId>@ares-pregame.<region>.pvp.net
 * @param {string} matchId - The pregame match UUID
 * @param {string} region - The region code
 * @returns {string} The full room JID
 */
function constructPregameRoomJid(matchId, region) {
  const r = region || currentRegion || 'ap';
  const jid = `${matchId}@ares-pregame.${r}.pvp.net`;
  emit({ type: 'debug', message: `Constructed PREGAME JID: ${jid}`, ts: Date.now() });
  return jid;
}

/**
 * Construct a coregame (in-game) team room JID
 * Format: <matchId>@ares-coregame.<region>.pvp.net
 * @param {string} matchId - The match UUID
 * @param {string} region - The region code
 * @returns {string} The full room JID
 */
function constructTeamRoomJid(matchId, region) {
  const r = region || currentRegion || 'ap';
  const jid = `${matchId}@ares-coregame.${r}.pvp.net`;
  emit({ type: 'debug', message: `Constructed TEAM JID: ${jid}`, ts: Date.now() });
  return jid;
}

/**
 * Construct a coregame (in-game) all-chat room JID
 * Format: <matchId>all@ares-coregame.<region>.pvp.net
 * @param {string} matchId - The match UUID
 * @param {string} region - The region code
 * @returns {string} The full room JID
 */
function constructAllChatRoomJid(matchId, region) {
  const r = region || currentRegion || 'ap';
  const jid = `${matchId}all@ares-coregame.${r}.pvp.net`;
  emit({ type: 'debug', message: `Constructed ALL JID: ${jid}`, ts: Date.now() });
  return jid;
}

/**
 * Extract game state from XMPP presence stanza and auto-join game rooms
 * The presence stanza contains base64-encoded JSON with game state info
 */
async function extractGameStateFromPresence(dataStr) {
  try {
    // Look for valorant game state in presence - it's in the <valorant> or <p> element as base64 JSON
    const b64Match = dataStr.match(/b64=['"]([^'"]+)['"]/);
    if (!b64Match) return;

    const b64Data = b64Match[1];
    const jsonStr = Buffer.from(b64Data, 'base64').toString('utf-8');
    let gameData;
    try {
      gameData = JSON.parse(jsonStr);
    } catch (_) {
      return; // Not valid JSON
    }

    // Check for game state indicators
    const loopState = gameData.sessionLoopState || gameData.partyOwnerSessionLoopState || gameData.loopState;
    const partyId = gameData.partyId || gameData.partyID;
    const matchId = gameData.matchId || gameData.coreGameId || gameData.gameId || gameData.matchmakingMatchId;
    const pregameMatchId = gameData.pregameId || gameData.preGameId || gameData.matchmakingPreGameId;

    // Create a state key to detect changes
    const stateKey = `${loopState}:${matchId || pregameMatchId || partyId}`;
    if (stateKey === lastExtractedGameState) return; // No change

    // If we detect INGAME or PREGAME, immediately try to join the appropriate room
    if (loopState === 'INGAME' && (matchId || partyId)) {
      const id = matchId || partyId;
      const roomKey = `game:${id}`;
      if (roomKey !== currentGameId) {
        lastExtractedGameState = stateKey;
        currentGameId = roomKey;
        const region = currentRegion || 'ap';

        // Join TEAM chat room
        const gameRoomJid = constructTeamRoomJid(id, region);
        emit({ type: 'info', message: `🎮 IN-GAME detected from presence! Joining rooms`, ts: Date.now() });
        await joinMUCRoom(gameRoomJid, currentPuuid.substring(0, 8));

        // Also join ALL chat room
        const allChatRoomJid = constructAllChatRoomJid(id, region);
        await joinMUCRoom(allChatRoomJid, currentPuuid.substring(0, 8));
      }
    } else if (loopState === 'PREGAME' && (pregameMatchId || partyId)) {
      const id = pregameMatchId || partyId;
      const roomKey = `pregame:${id}`;
      if (roomKey !== currentGameId) {
        lastExtractedGameState = stateKey;
        currentGameId = roomKey;
        const region = currentRegion || 'ap';

        const pregameRoomJid = constructPregameRoomJid(id, region);
        emit({ type: 'info', message: `⏳ PREGAME detected from presence! Joining room`, ts: Date.now() });
        await joinMUCRoom(pregameRoomJid, currentPuuid.substring(0, 8));
      }
    }
  } catch (e) {
    // Silently ignore parse errors
  }
}

/**
 * Immediately join current party/game rooms after successful XMPP connection
 * PREREQUISITE: setupMessageHandlers() must be called first
 */
async function joinCurrentActiveRooms() {
  if (!currentPuuid || !xmppSocket || xmppSocket.destroyed) {
    emit({ type: 'debug', message: 'joinCurrentActiveRooms: skipped (no puuid or socket)', ts: Date.now() });
    return;
  }

  // Safety check: never join before handlers are active
  if (!messageHandlersSetup) {
    emit({ type: 'error', error: 'CRITICAL: joinCurrentActiveRooms called before message handlers registered!', ts: Date.now() });
    return;
  }

  emit({ type: 'info', message: '🔍 Checking for active party/game rooms to join...', ts: Date.now() });

  try {
    const lockfilePath = path.join(
      process.env.LOCALAPPDATA || process.env.APPDATA,
      'Riot Games/Riot Client/Config/lockfile'
    );

    if (!fs.existsSync(lockfilePath)) return;

    const lockfileData = fs.readFileSync(lockfilePath, 'utf8');
    const [, , port, password, protocol] = lockfileData.split(':');
    const basicAuth = Buffer.from(`riot:${password}`).toString('base64');
    const baseUrl = `${protocol}://127.0.0.1:${port}`;

    // Get presences to find active sessions
    const presencesRes = await httpsRequest(`${baseUrl}/chat/v4/presences`, {
      headers: { 'Authorization': `Basic ${basicAuth}` },
      rejectUnauthorized: false,
      timeout: 5000
    });

    if (presencesRes.statusCode !== 200 || !presencesRes.data?.presences) return;

    const selfPresence = presencesRes.data.presences.find(p => p.puuid === currentPuuid);
    if (!selfPresence?.private) return;

    const privateJson = Buffer.from(selfPresence.private, 'base64').toString('utf-8');
    const privateData = JSON.parse(privateJson);

    const region = currentRegion || 'ap';
    const loop = privateData.sessionLoopState || privateData.loopState || '';
    const partyId = privateData.partyId || privateData.partyID;
    const pregameId = privateData.pregameId || privateData.preGameId;
    const coreGameId = privateData.matchId || privateData.coreGameId;

    emit({ type: 'debug', message: `Game state: loop=${loop}, region=${region}, partyId=${partyId || 'none'}, pregameId=${pregameId || 'none'}, coreGameId=${coreGameId || 'none'}`, ts: Date.now() });
    emit({ type: 'debug', message: `Current joinedRooms before join: ${joinedRooms.size} rooms`, ts: Date.now() });

    // Join appropriate rooms based on current state
    if (loop === 'INGAME' && coreGameId) {
      currentGameId = `game:${coreGameId}`;
      emit({ type: 'info', message: `🎮 IN-GAME detected! Joining team and all chat rooms`, ts: Date.now() });

      const teamRoomJid = constructTeamRoomJid(coreGameId, region);
      const allRoomJid = constructAllChatRoomJid(coreGameId, region);

      await joinMUCRoom(teamRoomJid, currentPuuid.substring(0, 8));
      await joinMUCRoom(allRoomJid, currentPuuid.substring(0, 8));
    }
    else if (loop === 'PREGAME' && pregameId) {
      currentGameId = `pregame:${pregameId}`;
      emit({ type: 'info', message: `⏳ PREGAME detected! Joining pregame room`, ts: Date.now() });

      const pregameRoomJid = constructPregameRoomJid(pregameId, region);

      await joinMUCRoom(pregameRoomJid, currentPuuid.substring(0, 8));
    }
    else if (partyId) {
      currentGameId = `party:${partyId}`;
      emit({ type: 'info', message: `🎉 PARTY detected! Joining party room`, ts: Date.now() });

      const partyRoomJid = constructPartyRoomJid(partyId, region);

      await joinMUCRoom(partyRoomJid, currentPuuid.substring(0, 8));
    }
    else {
      emit({ type: 'info', message: `ℹ️ No active party/game detected (loop=${loop})`, ts: Date.now() });
    }
  } catch (err) {
    emit({ type: 'warn', message: `Could not auto-join active rooms: ${err.message}`, ts: Date.now() });
  }
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
    currentRegion = region; // Store for room JID construction

    emit({ type: 'info', message: `Auth ready: region=${region}`, ts: Date.now() });
    const pasToken = await fetchPASToken(accessToken, entitlement);


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
    }

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
        emit({ type: 'error', error: `XMPP socket error: ${err.message}`, ts: Date.now() });
      });

      xmppSocket.on('close', () => {
        // Silent close during connection phase
      });

      await waitForConnect(xmppSocket);
    } catch (err) {
      connectError = err;
      try {
        xmppSocket = tls.connect({ ...tlsOptions, rejectUnauthorized: false });

        xmppSocket.on('error', (err) => {
          emit({ type: 'error', error: `XMPP socket error: ${err.message}`, ts: Date.now() });
        });

        xmppSocket.on('close', () => {
          // Silent close during retry
        });

        await waitForConnect(xmppSocket);
      } catch (err2) {
        throw err2;
      }
    }

    // Emit open-valorant event for Main.java UI status
    emit({ type: 'open-valorant', host: affinityHost, port: 5223, ts: Date.now() });

    emit({ type: 'info', message: 'Authenticating...', ts: Date.now() });

    await asyncSocketWrite(xmppSocket, `<?xml version="1.0"?><stream:stream to="${affinityDomain}.pvp.net" version="1.0" xmlns="jabber:client" xmlns:stream="http://etherx.jabber.org/streams">`);
    let incomingData = '';
    do {
      incomingData = await asyncSocketRead(xmppSocket);
    } while (!incomingData.includes('X-Riot-RSO-PAS'));

    await asyncSocketWrite(xmppSocket, `<auth mechanism="X-Riot-RSO-PAS" xmlns="urn:ietf:params:xml:ns:xmpp-sasl"><rso_token>${accessToken}</rso_token><pas_token>${pasToken}</pas_token></auth>`);
    await asyncSocketRead(xmppSocket);

    await asyncSocketWrite(xmppSocket, `<?xml version="1.0"?><stream:stream to="${affinityDomain}.pvp.net" version="1.0" xmlns="jabber:client" xmlns:stream="http://etherx.jabber.org/streams">`);
    do {
      incomingData = await asyncSocketRead(xmppSocket);
    } while (!incomingData.includes('stream:features'));

    await asyncSocketWrite(xmppSocket, '<iq id="_xmpp_bind1" type="set"><bind xmlns="urn:ietf:params:xml:ns:xmpp-bind"></bind></iq>');
    const bindResponse = await asyncSocketRead(xmppSocket);
    emit({ type: 'incoming', time: Date.now(), data: bindResponse });

    await asyncSocketWrite(xmppSocket, '<iq id="_xmpp_session1" type="set"><session xmlns="urn:ietf:params:xml:ns:xmpp-session"/></iq>');
    await asyncSocketRead(xmppSocket);

    await asyncSocketWrite(xmppSocket, `<iq id="xmpp_entitlements_0" type="set"><entitlements xmlns="urn:riotgames:entitlements"><token xmlns="">${entitlement}</token></entitlements></iq>`);
    await asyncSocketRead(xmppSocket);

    // Emit open-riot event for Main.java UI status (auth complete)
    emit({ type: 'open-riot', ts: Date.now() });
    emit({ type: 'info', message: 'Connected to Riot XMPP server', ts: Date.now() });

    // ========== CRITICAL: ValorantNarrator-style initialization order ==========
    // STEP 1: Reset handler flag for fresh registration (but preserve joinedRooms for reconnect)
    //         joinedRooms is ONLY cleared on socket close (true disconnect)
    messageHandlersSetup = false;
    if (joinedRooms.size > 0) {
      emit({ type: 'info', message: `📋 Preserving ${joinedRooms.size} previously joined rooms: [${Array.from(joinedRooms).map(r => r.split('@')[0].substring(0, 8) + '...').join(', ')}]`, ts: Date.now() });
    } else {
      emit({ type: 'debug', message: '📋 No previously joined rooms (fresh connection)', ts: Date.now() });
    }

    // STEP 2: Register ALL message/stanza handlers FIRST (before ANY stanzas are sent)
    //         This ensures we NEVER miss any incoming messages including groupchat
    setupMessageHandlers();
    emit({ type: 'info', message: '✅ Message handlers registered', ts: Date.now() });

    // STEP 3: Enable XMPP Carbons BEFORE any presence is sent
    //         Carbons ensure we receive copies of messages sent from other clients (and our own)
    //         This is critical for party/team message reception
    await enableCarbons();

    // STEP 4: Start keepalive (handlers are active, safe to do this now)
    startKeepalive();

    // STEP 5: NOW it's safe to send presence and roster queries
    //         All responses will be captured by handlers registered in Step 2
    emit({ type: 'info', message: '✅ Safe to join rooms - sending initial presence', ts: Date.now() });
    await asyncSocketWrite(xmppSocket, '<iq type="get" id="roster_1"><query xmlns="jabber:iq:riotgames:roster" last_state="true"/></iq>');
    await asyncSocketWrite(xmppSocket, '<iq type="get" id="recent_convos_2"><query xmlns="jabber:iq:riotgames:archive:list"/></iq>');
    await asyncSocketWrite(xmppSocket, '<presence/>');

    // STEP 6: Join active rooms - handlers are ready, carbons enabled, all responses will be captured
    await joinCurrentActiveRooms();

    // STEP 7: Start game room monitor to detect game state changes dynamically
    startGameRoomMonitor(accessToken, authData);
    // ========== End ValorantNarrator-style initialization ==========

  } catch (error) {
    emit({ type: 'error', error: `Connection failed: ${error.message}`, ts: Date.now() });

    if (!isShuttingDown) {
      emit({ type: 'info', message: 'Reconnecting in 10 seconds...', ts: Date.now() });
      reconnectTimer = setTimeout(() => connectXMPP(), 10000);
    }
  }
}

/**
 * Setup handlers for incoming XMPP messages
 */
let messageHandlersSetup = false;
function setupMessageHandlers() {
  if (!xmppSocket) return;

  // Prevent duplicate handler registration
  if (messageHandlersSetup) {
    emit({ type: 'debug', message: 'Message handlers already setup, skipping', ts: Date.now() });
    return;
  }
  messageHandlersSetup = true;
  emit({ type: 'info', message: '✅ Message handler registered - ready to receive MUC messages', ts: Date.now() });

  xmppSocket.setTimeout(300000); // 5 min idle timeout
  xmppSocket.on('timeout', () => {
    emit({ type: 'info', message: 'XMPP idle timeout; closing and reconnecting', ts: Date.now() });
    messageHandlersSetup = false; // Reset on timeout
    try { xmppSocket.destroy(); } catch (_) {}
  });

  xmppSocket.on('data', (data) => {
    const dataStr = data.toString();

    emit({
      type: 'incoming',
      time: Date.now(),
      data: dataStr
    });

    // Log MUC-related presence stanzas for debugging room joins
    if (dataStr.includes('<presence')) {
      const roomJid = extractRoomJid(dataStr);

      // Check if this is a MUC room presence (join confirmation or occupant update)
      if (roomJid) {
        // Check for MUC namespace which confirms it's a room presence
        if (dataStr.includes('http://jabber.org/protocol/muc')) {
          emit({ type: 'info', message: `📥 MUC presence from: ${roomJid}`, ts: Date.now() });
        }

        // Auto-join if we see presence from a room we're not in
        if (currentPuuid && !joinedRooms.has(roomJid)) {
          emit({ type: 'debug', message: `Auto-joining room from presence: ${roomJid}`, ts: Date.now() });
          joinMUCRoom(roomJid, currentPuuid.substring(0, 8));
        }
      }

      // Check if this presence contains game state info (for auto-room-join)
      if (currentPuuid && dataStr.includes(currentPuuid)) {
        extractGameStateFromPresence(dataStr);
      }
    }

    // Enhanced message detection for ALL messages (MUC, party, whispers)
    if (dataStr.includes('<message')) {
      const fromMatch = dataStr.match(/from=['"]([^'\"]+)['"]/);
      const typeMatch = dataStr.match(/type=['"]([^'\"]+)['"]/);
      // FIXED: Improved regex to handle multi-line content and HTML entities
      const bodyMatch = dataStr.match(/<body>([\s\S]*?)<\/body>/);

      // Safety: ensure we have valid matches before processing
      if (fromMatch && fromMatch[1]) {
        const from = fromMatch[1];
        const msgType = typeMatch && typeMatch[1] ? typeMatch[1] : 'unknown';
        const body = bodyMatch && bodyMatch[1] ? bodyMatch[1] : '';

        // Determine message category based on domain
        const isMucMessage = from.includes('@ares-parties') ||
                            from.includes('@ares-pregame') ||
                            from.includes('@ares-coregame');

        // Extract room type for logging
        let roomType = 'WHISPER';
        if (from.includes('@ares-parties')) roomType = 'PARTY';
        else if (from.includes('@ares-pregame')) roomType = 'PREGAME';
        else if (from.includes('all@ares-coregame')) roomType = 'ALL';
        else if (from.includes('@ares-coregame')) roomType = 'TEAM';

        // ════════════════════════════════════════════════════════════════
        // CRITICAL: Log EVERY <message type="groupchat"> stanza received
        // ════════════════════════════════════════════════════════════════
        if (msgType === 'groupchat') {
          const senderNick = from.split('/')[1] || 'unknown';
          const roomId = from.split('@')[0] || 'unknown';

          emit({
            type: 'info',
            message: `════════════════════════════════════════`,
            ts: Date.now()
          });
          emit({
            type: 'info',
            message: `🎉 GROUPCHAT STANZA RECEIVED`,
            ts: Date.now()
          });
          emit({
            type: 'info',
            message: `   Room Type: ${roomType}`,
            ts: Date.now()
          });
          emit({
            type: 'info',
            message: `   Room ID: ${roomId.substring(0, 12)}...`,
            ts: Date.now()
          });
          emit({
            type: 'info',
            message: `   Sender: ${senderNick.substring(0, 12)}...`,
            ts: Date.now()
          });
          emit({
            type: 'info',
            message: `   Body: "${body.substring(0, 50)}${body.length > 50 ? '...' : ''}"`,
            ts: Date.now()
          });
          emit({
            type: 'info',
            message: `════════════════════════════════════════`,
            ts: Date.now()
          });

          // Emit structured groupchat event for Java to process
          emit({
            type: 'groupchat-received',
            roomType: roomType,
            roomId: roomId,
            sender: senderNick,
            from: from,
            body: body,
            ts: Date.now()
          });
        }

        // Log with details for all message types with body
        if (body && body.trim().length > 0) {
          const bodyPreview = body.length > 50 ? body.substring(0, 47) + '...' : body;
          const fromShort = from.split('/')[1] || from.split('@')[0].substring(0, 8);

          emit({
            type: 'info',
            message: `💬 [${roomType}][type=${msgType}] ${fromShort}: ${bodyPreview}`,
            ts: Date.now()
          });

          // Additional structured event for debugging
          emit({
            type: 'message-detected',
            roomType: roomType,
            messageType: msgType,
            from: from,
            isMuc: isMucMessage,
            hasBody: true,
            ts: Date.now()
          });
        }
      }
    }
  });

  xmppSocket.on('error', (err) => {
    emit({ type: 'error', error: `Socket error: ${err.message}`, ts: Date.now() });
  });

  xmppSocket.on('close', () => {
    emit({ type: 'info', message: 'XMPP connection closed', ts: Date.now() });

    if (heartbeatTimer) {
      clearInterval(heartbeatTimer);
      heartbeatTimer = null;
    }

    // Clear joined rooms on disconnect
    joinedRooms.clear();

    // Reset handler flag so handlers can be re-registered on reconnect
    messageHandlersSetup = false;

    if (!isShuttingDown) {
      reconnectTimer = setTimeout(() => connectXMPP(), 5000);
    }
  });
}

/**
 * ============================================================================
 * GAME STATE MONITORING (ValorantNarrator-style)
 * ============================================================================
 *
 * Polls Riot local APIs to detect game state changes:
 *   - partyId: Current party UUID
 *   - pregameId: Agent select match UUID
 *   - coreGameId: In-game match UUID
 *
 * POLLING INTERVAL: 2000ms (2 seconds)
 *
 * State transitions trigger:
 *   - Joining new MUC rooms
 *   - Leaving obsolete rooms (logged)
 *   - Rejoin if rooms were missed
 *
 * Data sources (in priority order):
 *   1. Valorant GLZ API (most reliable) - /core-game/v1/player, /pregame/v1/player
 *   2. Riot Client presence API (fallback) - /chat/v4/presences
 * ============================================================================
 */

// Polling interval in milliseconds
const GAME_STATE_POLL_INTERVAL = 2000;

// Game state tracking
let gameRoomMonitorTimer = null;
let lastGameState = {
  loopState: null,
  partyId: null,
  pregameId: null,
  coreGameId: null,
  timestamp: null
};

// Track rooms we've joined for each state type
let currentStateRooms = new Set();

/**
 * Get game state snapshot from available APIs
 * Returns: { loopState, partyId, pregameId, coreGameId, source }
 */
async function getGameStateSnapshot(riotPort, riotPassword, riotProtocol, region) {
  const snapshot = {
    loopState: 'MENUS',
    partyId: null,
    pregameId: null,
    coreGameId: null,
    source: 'none',
    region: region || currentRegion || 'ap'
  };

  const riotBasicAuth = Buffer.from(`riot:${riotPassword}`).toString('base64');
  const riotBaseUrl = `${riotProtocol}://127.0.0.1:${riotPort}`;

  // === SOURCE 1: Valorant GLZ API (most reliable for game state) ===
  const valLockfilePath = findValorantLockfile();

  if (valLockfilePath) {
    try {
      const valLockfileData = fs.readFileSync(valLockfilePath, 'utf8');
      const [, , valPort, valPassword] = valLockfileData.split(':');
      const valBasicAuth = Buffer.from(`riot:${valPassword}`).toString('base64');
      const valBaseUrl = `https://127.0.0.1:${valPort}`;

      // Try core-game endpoint first (highest priority)
      try {
        const coreGameRes = await httpsRequest(`${valBaseUrl}/core-game/v1/player/${currentPuuid}`, {
          headers: { 'Authorization': `Basic ${valBasicAuth}` },
          rejectUnauthorized: false,
          timeout: 2000
        });
        if (coreGameRes.statusCode === 200 && coreGameRes.data?.MatchID) {
          snapshot.coreGameId = coreGameRes.data.MatchID;
          snapshot.loopState = 'INGAME';
          snapshot.source = 'GLZ-coregame';
        }
      } catch (_) {}

      // Try pregame endpoint if not in core game
      if (!snapshot.coreGameId) {
        try {
          const pregameRes = await httpsRequest(`${valBaseUrl}/pregame/v1/player/${currentPuuid}`, {
            headers: { 'Authorization': `Basic ${valBasicAuth}` },
            rejectUnauthorized: false,
            timeout: 2000
          });
          if (pregameRes.statusCode === 200 && pregameRes.data?.MatchID) {
            snapshot.pregameId = pregameRes.data.MatchID;
            snapshot.loopState = 'PREGAME';
            snapshot.source = 'GLZ-pregame';
          }
        } catch (_) {}
      }
    } catch (_) {}
  }

  // === SOURCE 2: Riot Client Presence API (fallback, also gets partyId) ===
  try {
    const presencesRes = await httpsRequest(`${riotBaseUrl}/chat/v4/presences`, {
      headers: { 'Authorization': `Basic ${riotBasicAuth}` },
      rejectUnauthorized: false,
      timeout: 3000
    });

    if (presencesRes.statusCode === 200 && presencesRes.data?.presences) {
      const selfPresence = presencesRes.data.presences.find(p => p.puuid === currentPuuid);

      if (selfPresence?.private) {
        const privateJson = Buffer.from(selfPresence.private, 'base64').toString('utf-8');
        const privateData = JSON.parse(privateJson);

        // Always extract partyId from presence (GLZ doesn't provide it)
        snapshot.partyId = privateData.partyId || privateData.partyID || null;

        // If GLZ didn't give us state, use presence data
        if (snapshot.source === 'none') {
          const loop = privateData.sessionLoopState ||
                       privateData.partyOwnerSessionLoopState ||
                       privateData.loopState ||
                       'MENUS';

          snapshot.loopState = loop;

          if (loop === 'INGAME') {
            snapshot.coreGameId = privateData.matchId ||
                                  privateData.coreGameId ||
                                  privateData.gameId ||
                                  privateData.matchmakingMatchId || null;
            snapshot.source = 'presence-ingame';
          } else if (loop === 'PREGAME') {
            snapshot.pregameId = privateData.pregameId ||
                                 privateData.preGameId ||
                                 privateData.matchmakingPreGameId || null;
            snapshot.source = 'presence-pregame';
          } else {
            snapshot.source = 'presence-menus';
          }
        } else {
          // Merge presence partyId with GLZ data
          snapshot.source += '+presence';
        }
      }
    }
  } catch (_) {}

  return snapshot;
}

/**
 * Find Valorant lockfile path
 */
function findValorantLockfile() {
  const possiblePaths = [
    'C:/Riot Games/VALORANT/live/ShooterGame/Binaries/Win64',
    'D:/Riot Games/VALORANT/live/ShooterGame/Binaries/Win64',
    'E:/Riot Games/VALORANT/live/ShooterGame/Binaries/Win64',
    'F:/Riot Games/VALORANT/live/ShooterGame/Binaries/Win64',
    'C:/Games/VALORANT/live/ShooterGame/Binaries/Win64',
    'D:/Games/VALORANT/live/ShooterGame/Binaries/Win64',
    path.join(process.env.ProgramFiles || 'C:/Program Files', 'Riot Games/VALORANT/live/ShooterGame/Binaries/Win64'),
    path.join(process.env['ProgramFiles(x86)'] || 'C:/Program Files (x86)', 'Riot Games/VALORANT/live/ShooterGame/Binaries/Win64'),
    'C:/Riot Games/VALORANT/ShooterGame/Binaries/Win64',
    'D:/Riot Games/VALORANT/ShooterGame/Binaries/Win64',
  ];

  for (const dir of possiblePaths) {
    try {
      if (fs.existsSync(dir)) {
        const files = fs.readdirSync(dir);
        const lockFile = files.find(f => f.toLowerCase() === 'lockfile');
        if (lockFile) {
          return path.join(dir, lockFile);
        }
      }
    } catch (_) {}
  }
  return null;
}

/**
 * Detect state transition and return transition info
 */
function detectStateTransition(oldState, newState) {
  const transition = {
    changed: false,
    type: null,
    from: null,
    to: null,
    roomsToJoin: [],
    roomsToLeave: []
  };

  // Check for loop state change
  if (oldState.loopState !== newState.loopState) {
    transition.changed = true;
    transition.type = 'loopState';
    transition.from = oldState.loopState;
    transition.to = newState.loopState;
  }

  // Check for party change
  if (oldState.partyId !== newState.partyId) {
    transition.changed = true;
    if (!transition.type) {
      transition.type = 'party';
      transition.from = oldState.partyId?.substring(0, 8) || 'none';
      transition.to = newState.partyId?.substring(0, 8) || 'none';
    }

    // Old party room should be left
    if (oldState.partyId) {
      transition.roomsToLeave.push(constructPartyRoomJid(oldState.partyId, newState.region));
    }
    // New party room should be joined
    if (newState.partyId && newState.loopState !== 'INGAME' && newState.loopState !== 'PREGAME') {
      transition.roomsToJoin.push({ jid: constructPartyRoomJid(newState.partyId, newState.region), type: 'PARTY' });
    }
  }

  // Check for pregame change
  if (oldState.pregameId !== newState.pregameId) {
    transition.changed = true;
    if (!transition.type) {
      transition.type = 'pregame';
      transition.from = oldState.pregameId?.substring(0, 8) || 'none';
      transition.to = newState.pregameId?.substring(0, 8) || 'none';
    }

    // Old pregame room should be left
    if (oldState.pregameId) {
      transition.roomsToLeave.push(constructPregameRoomJid(oldState.pregameId, newState.region));
    }
    // New pregame room should be joined
    if (newState.pregameId && newState.loopState === 'PREGAME') {
      transition.roomsToJoin.push({ jid: constructPregameRoomJid(newState.pregameId, newState.region), type: 'PREGAME' });
    }
  }

  // Check for coregame change
  if (oldState.coreGameId !== newState.coreGameId) {
    transition.changed = true;
    if (!transition.type) {
      transition.type = 'coregame';
      transition.from = oldState.coreGameId?.substring(0, 8) || 'none';
      transition.to = newState.coreGameId?.substring(0, 8) || 'none';
    }

    // Old coregame rooms should be left
    if (oldState.coreGameId) {
      transition.roomsToLeave.push(constructTeamRoomJid(oldState.coreGameId, newState.region));
      transition.roomsToLeave.push(constructAllChatRoomJid(oldState.coreGameId, newState.region));
    }
    // New coregame rooms should be joined
    if (newState.coreGameId && newState.loopState === 'INGAME') {
      transition.roomsToJoin.push({ jid: constructTeamRoomJid(newState.coreGameId, newState.region), type: 'TEAM' });
      transition.roomsToJoin.push({ jid: constructAllChatRoomJid(newState.coreGameId, newState.region), type: 'ALL' });
    }
  }

  return transition;
}

/**
 * Leave a MUC room (send unavailable presence)
 */
async function leaveMUCRoomIfJoined(roomJid) {
  if (!joinedRooms.has(roomJid)) {
    return; // Not in this room
  }

  if (!xmppSocket || xmppSocket.destroyed) {
    return;
  }

  try {
    const nick = currentPuuid ? currentPuuid.substring(0, 8) : 'valvoice';
    const presenceStanza = `<presence to="${roomJid}/${nick}" type="unavailable"/>`;
    await asyncSocketWrite(xmppSocket, presenceStanza);
    joinedRooms.delete(roomJid);
    currentStateRooms.delete(roomJid);

    const roomType = roomJid.includes('@ares-parties') ? 'PARTY' :
                     roomJid.includes('@ares-pregame') ? 'PREGAME' :
                     roomJid.includes('all@ares-coregame') ? 'ALL' :
                     roomJid.includes('@ares-coregame') ? 'TEAM' : 'UNKNOWN';

    emit({ type: 'info', message: `👋 [${roomType}] Left room: ${roomJid.split('@')[0].substring(0, 12)}...`, ts: Date.now() });
    emit({ type: 'room-left', room: roomJid, roomType: roomType, ts: Date.now() });
  } catch (err) {
    emit({ type: 'debug', message: `Failed to leave room ${roomJid}: ${err.message}`, ts: Date.now() });
  }
}

/**
 * Check if we need to rejoin any rooms (missed due to disconnect etc)
 */
async function rejoinMissedRooms(currentState) {
  const expectedRooms = [];
  const region = currentState.region;

  // Determine which rooms we SHOULD be in based on current state
  if (currentState.loopState === 'INGAME' && currentState.coreGameId) {
    expectedRooms.push(constructTeamRoomJid(currentState.coreGameId, region));
    expectedRooms.push(constructAllChatRoomJid(currentState.coreGameId, region));
  } else if (currentState.loopState === 'PREGAME' && currentState.pregameId) {
    expectedRooms.push(constructPregameRoomJid(currentState.pregameId, region));
  } else if (currentState.partyId) {
    expectedRooms.push(constructPartyRoomJid(currentState.partyId, region));
  }

  // Check for missing rooms and rejoin
  for (const roomJid of expectedRooms) {
    if (!joinedRooms.has(roomJid)) {
      emit({ type: 'info', message: `🔄 Rejoining missed room: ${roomJid.split('@')[0].substring(0, 12)}...`, ts: Date.now() });
      await joinMUCRoom(roomJid, currentPuuid?.substring(0, 8) || 'valvoice');
    }
  }
}

/**
 * Log game state snapshot
 */
function logGameStateSnapshot(state, isTransition = false) {
  const stateEmoji = state.loopState === 'INGAME' ? '🎮' :
                     state.loopState === 'PREGAME' ? '⏳' :
                     state.partyId ? '🎉' : '🏠';

  const partyShort = state.partyId ? state.partyId.substring(0, 8) + '...' : 'none';
  const pregameShort = state.pregameId ? state.pregameId.substring(0, 8) + '...' : 'none';
  const coreGameShort = state.coreGameId ? state.coreGameId.substring(0, 8) + '...' : 'none';

  if (isTransition) {
    emit({
      type: 'info',
      message: `${stateEmoji} Game State Transition: loop=${state.loopState} party=${partyShort} pregame=${pregameShort} game=${coreGameShort} [${state.source}]`,
      ts: Date.now()
    });
  } else {
    emit({
      type: 'debug',
      message: `${stateEmoji} State: loop=${state.loopState} party=${partyShort} pregame=${pregameShort} game=${coreGameShort} [${state.source}]`,
      ts: Date.now()
    });
  }
}

/**
 * Main game state monitor - polls APIs and handles state changes
 * POLLING INTERVAL: 2000ms (2 seconds)
 */
async function startGameRoomMonitor(accessToken, authData) {
  if (gameRoomMonitorTimer) {
    clearInterval(gameRoomMonitorTimer);
    gameRoomMonitorTimer = null;
  }

  emit({ type: 'info', message: `🔍 Starting game state monitor (poll interval: ${GAME_STATE_POLL_INTERVAL}ms)`, ts: Date.now() });

  const checkGameState = async () => {
    if (!xmppSocket || xmppSocket.destroyed || !currentPuuid) {
      return;
    }

    try {
      // Get Riot Client lockfile
      const lockfilePath = path.join(
        process.env.LOCALAPPDATA || process.env.APPDATA,
        'Riot Games/Riot Client/Config/lockfile'
      );

      if (!fs.existsSync(lockfilePath)) {
        return;
      }

      const lockfileData = fs.readFileSync(lockfilePath, 'utf8');
      const [, , port, password, protocol] = lockfileData.split(':');
      const region = currentRegion || authData?.region || 'ap';

      // Get current game state snapshot
      const currentState = await getGameStateSnapshot(port, password, protocol, region);
      currentState.timestamp = Date.now();

      // Detect state transition
      const transition = detectStateTransition(lastGameState, currentState);

      if (transition.changed) {
        // Log the transition
        emit({
          type: 'info',
          message: `🔄 State transition detected: ${transition.type} (${transition.from} → ${transition.to})`,
          ts: Date.now()
        });
        logGameStateSnapshot(currentState, true);

        // Leave old rooms
        for (const roomJid of transition.roomsToLeave) {
          await leaveMUCRoomIfJoined(roomJid);
        }

        // Join new rooms
        for (const room of transition.roomsToJoin) {
          emit({ type: 'info', message: `🚪 Joining [${room.type}] due to transition`, ts: Date.now() });
          await joinMUCRoom(room.jid, currentPuuid?.substring(0, 8) || 'valvoice');
          currentStateRooms.add(room.jid);
        }

        // Update last state
        lastGameState = { ...currentState };

        // Log rooms joined due to transition
        if (transition.roomsToJoin.length > 0) {
          emit({
            type: 'info',
            message: `📋 Rooms joined due to transition: ${transition.roomsToJoin.map(r => r.type).join(', ')}`,
            ts: Date.now()
          });
        }
      } else {
        // No transition, but check for missed rooms (rejoin logic)
        await rejoinMissedRooms(currentState);
      }

    } catch (err) {
      // Log errors but don't spam
      emit({ type: 'debug', message: `Game state check error: ${err.message}`, ts: Date.now() });
    }
  };

  // Run immediately, then poll at interval
  await checkGameState();
  gameRoomMonitorTimer = setInterval(checkGameState, GAME_STATE_POLL_INTERVAL);

  emit({ type: 'info', message: `✅ Game state monitor started`, ts: Date.now() });
}

/**
 * Start keepalive timer
 */
function startKeepalive() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer);
  }

  heartbeatTimer = setInterval(() => {
    if (xmppSocket && !xmppSocket.destroyed) {
      asyncSocketWrite(xmppSocket, ' ').catch(() => {});
    }
  }, 150000);
}

/**
 * Graceful shutdown
 */
function shutdown(reason) {
  if (isShuttingDown) return;
  isShuttingDown = true;

  if (reconnectTimer) clearTimeout(reconnectTimer);
  if (heartbeatTimer) clearInterval(heartbeatTimer);
  if (gameRoomMonitorTimer) clearInterval(gameRoomMonitorTimer);

  // Close MITM proxy connections
  if (clientSocket && !clientSocket.destroyed) {
    clientSocket.destroy();
  }
  if (serverSocket && !serverSocket.destroyed) {
    serverSocket.destroy();
  }
  if (proxyServer) {
    proxyServer.close();
  }

  // Close direct connection
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
  shutdown('uncaughtException');
});

process.on('unhandledRejection', (reason, promise) => {
  emit({ type: 'error', error: `Unhandled rejection: ${reason}`, ts: Date.now() });
});

// ============ ENTRY POINT ============
// Check environment variable or command line arg for mode selection
const USE_MITM_PROXY = process.env.VALVOICE_MITM === 'true' || process.argv.includes('--mitm');

if (USE_MITM_PROXY) {
  emit({ type: 'info', message: '🔧 Starting in MITM Proxy mode', ts: Date.now() });
  emit({ type: 'info', message: '⚠️  IMPORTANT: Add these lines to C:\\Windows\\System32\\drivers\\etc\\hosts:', ts: Date.now() });
  emit({ type: 'info', message: '127.0.0.1 jp1.chat.si.riotgames.com', ts: Date.now() });
  emit({ type: 'info', message: '127.0.0.1 ap1.chat.si.riotgames.com', ts: Date.now() });
  emit({ type: 'info', message: '127.0.0.1 na2.chat.si.riotgames.com', ts: Date.now() });
  emit({ type: 'info', message: '127.0.0.1 eu.chat.si.riotgames.com', ts: Date.now() });
  emit({ type: 'info', message: '127.0.0.1 kr.chat.si.riotgames.com', ts: Date.now() });
  emit({ type: 'info', message: '(Add all regions you play in)', ts: Date.now() });
  startMITMProxy();
} else {
  emit({ type: 'info', message: '🔧 Starting in Direct Connection mode (no outgoing message capture)', ts: Date.now() });
  connectXMPP();
}

