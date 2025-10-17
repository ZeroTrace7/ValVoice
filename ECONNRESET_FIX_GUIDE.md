# ECONNRESET Error Fix - Complete Guide

## Problem Analysis

The `ECONNRESET` error (errno: -4077) you were experiencing was caused by **connection pooling issues** when fetching the PAS token from Riot's servers.

### Root Causes:
1. **HTTP Keep-Alive**: The Node.js HTTPS agent was reusing connections that Riot servers had already closed
2. **Insufficient Retry Logic**: Only 3 retries with short delays weren't enough for unstable connections
3. **Timeout Issues**: 30-second timeout was too short for some regional network conditions
4. **No Connection Cleanup**: Agents weren't being properly destroyed after requests

## What Was Fixed

### 1. **Disabled Keep-Alive Connections**
```javascript
// Added global keep-alive disable
http.globalAgent.keepAlive = false;
https.globalAgent.keepAlive = false;

// Each request now uses a fresh agent
const agent = new https.Agent({
  keepAlive: false,
  maxSockets: 1,
  maxFreeSockets: 0,
  timeout: 60000,
  scheduling: 'fifo'
});
```

### 2. **Enhanced Retry Logic**
- Increased retries: **3 → 5 attempts**
- Longer base delay: **1000ms → 3000ms**
- Better backoff: exponential with 1.5x multiplier
- Added delay before each PAS token attempt (500ms)

### 3. **Increased Timeouts**
- Request timeout: **30s → 60s**
- Agent timeout: **30s → 60s**
- Better for high-latency connections

### 4. **Proper Resource Cleanup**
```javascript
req.on('error', (err) => {
  agent.destroy();  // Always cleanup on error
  reject(err);
});
```

### 5. **More Retryable Error Codes**
Added handling for:
- `EPIPE` - Broken pipe
- `ENOTFOUND` - DNS resolution failure
- `EAI_AGAIN` - Temporary DNS failure
- Socket hang up errors

### 6. **Connection Header**
```javascript
headers: { 
  'Connection': 'close',  // Explicitly close after each request
  ...
}
```

## Why It Works

1. **No Connection Reuse**: Each request creates a fresh connection, preventing "stale connection" errors
2. **More Attempts**: 5 retries with longer delays give the network more chances to stabilize
3. **Longer Timeouts**: Accommodates slower network conditions (especially in JP1 region)
4. **Better Cleanup**: Prevents socket leaks and ensures resources are freed

## Testing the Fix

Run the diagnostic script to verify your connection:

```bash
node test-xmpp-connection.js
```

This will test:
1. Riot Client connection
2. Local authentication
3. Chat session status
4. **PAS token fetching with retries** (the main fix)
5. Overall connection health

## Expected Behavior

### Before Fix:
```
[XmppBridge:info] Fetching PAS token...
XMPP error event: PAS token fetch error: read ECONNRESET
XMPP error event: Connection failed: read ECONNRESET
[XmppBridge:info] Retrying connection in 10 seconds...
```

### After Fix:
```
[XmppBridge:debug] Starting PAS token fetch with enhanced retry logic...
[XmppBridge:debug] Request failed (ECONNRESET), retrying in 3000ms (attempt 1/5)
[XmppBridge:debug] Request failed (ECONNRESET), retrying in 4500ms (attempt 2/5)
[XmppBridge:debug] PAS token response status: 200
[XmppBridge:debug] PAS token received as string
[XmppBridge:info] Got entitlements token from local client
✅ Connected to Riot XMPP server
```

## Additional Notes

### For Japan Region (JP1)
Your region may have higher latency or stricter network policies. The enhanced retry logic specifically helps with:
- Network fluctuations
- Rate limiting
- Temporary connection drops

### If Issues Persist

If you still see ECONNRESET errors after 5 attempts:

1. **Check Firewall/Antivirus**: Ensure `riot-geo.pas.si.riotgames.com` is allowed
2. **Disable VPN**: VPNs can interfere with Riot's geo-location services
3. **Network Stability**: Check if other Riot services work properly
4. **ISP Issues**: Some ISPs throttle gaming traffic

The application will **automatically retry** every 10 seconds, so temporary issues will resolve themselves.

## Technical Details

### Modified Files:
- `xmpp-bridge/index.js` - Main XMPP bridge implementation

### Key Functions Updated:
- `httpsRequest()` - Complete rewrite with agent management
- `retryRequest()` - Enhanced retry logic
- `fetchPASToken()` - Added delays and better error reporting
- `fetchEntitlementsToken()` - Increased retries and timeout
- `getRiotConfig()` - Increased retries and timeout

### Performance Impact:
- **Slightly slower initial connection** (due to delays between retries)
- **More reliable connections** (fewer failures)
- **Better resource usage** (proper cleanup prevents leaks)

---

**Status**: ✅ Fix Applied and Tested
**Version**: 2.2.1-fixed
**Date**: 2025-10-18

