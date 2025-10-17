#!/usr/bin/env node
/**
 * Test script to diagnose XMPP connection issues
 * Tests PAS token fetching with detailed diagnostics
 */

const https = require('https');
const fs = require('fs');
const path = require('path');

console.log('=== ValVoice XMPP Connection Diagnostics ===\n');

// Test 1: Check Riot Client is running
console.log('[1/5] Checking Riot Client...');
const lockfilePath = path.join(
  process.env.LOCALAPPDATA || process.env.APPDATA,
  'Riot Games/Riot Client/Config/lockfile'
);

if (!fs.existsSync(lockfilePath)) {
  console.error('❌ Riot Client lockfile not found. Please start Riot Client and Valorant.');
  process.exit(1);
}

const lockfileData = fs.readFileSync(lockfilePath, 'utf8');
const [name, pid, port, password, protocol] = lockfileData.split(':');
console.log(`✅ Riot Client running (port ${port})\n`);

// Test 2: Get local auth tokens
console.log('[2/5] Fetching local auth tokens...');
const basicAuth = Buffer.from(`riot:${password}`).toString('base64');
const baseUrl = `${protocol}://127.0.0.1:${port}`;

function httpsRequest(url, options = {}) {
  return new Promise((resolve, reject) => {
    const urlObj = new URL(url);
    const agent = new https.Agent({
      keepAlive: false,
      maxSockets: 1,
      maxFreeSockets: 0,
      timeout: 60000
    });

    const reqOptions = {
      hostname: urlObj.hostname,
      port: urlObj.port || 443,
      path: urlObj.pathname + urlObj.search,
      method: options.method || 'GET',
      headers: {
        'User-Agent': 'ValVoice-Test/1.0',
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
        agent.destroy();
        try {
          resolve({ statusCode: res.statusCode, data: JSON.parse(data) });
        } catch (e) {
          resolve({ statusCode: res.statusCode, data: data });
        }
      });
    });

    req.on('error', (err) => {
      agent.destroy();
      reject(err);
    });

    req.setTimeout(60000, () => {
      req.destroy();
      agent.destroy();
      reject(new Error('Request timeout'));
    });

    if (options.body) req.write(options.body);
    req.end();
  });
}

async function test() {
  try {
    // Get entitlements
    const entitlementsResponse = await httpsRequest(`${baseUrl}/entitlements/v1/token`, {
      headers: { 'Authorization': `Basic ${basicAuth}` },
      rejectUnauthorized: false
    });

    if (entitlementsResponse.statusCode !== 200) {
      console.error(`❌ Failed to get entitlements (status ${entitlementsResponse.statusCode})`);
      process.exit(1);
    }

    const accessToken = entitlementsResponse.data.accessToken;
    const entitlement = entitlementsResponse.data.token || entitlementsResponse.data.entitlements_token;
    console.log(`✅ Got local tokens\n`);

    // Test 3: Check chat session
    console.log('[3/5] Checking chat session...');
    const chatResponse = await httpsRequest(`${baseUrl}/chat/v1/session`, {
      headers: { 'Authorization': `Basic ${basicAuth}` },
      rejectUnauthorized: false
    });

    if (chatResponse.statusCode !== 200 || !chatResponse.data.loaded) {
      console.error('❌ Chat session not ready. Please open Valorant and wait for it to fully load.');
      process.exit(1);
    }

    console.log(`✅ Chat session loaded (region: ${chatResponse.data.region})\n`);

    // Test 4: Test PAS token endpoint with retries
    console.log('[4/5] Testing PAS token endpoint (this may take a moment)...');
    let pasSuccess = false;
    let lastError = null;

    for (let attempt = 1; attempt <= 5; attempt++) {
      try {
        console.log(`  Attempt ${attempt}/5...`);

        // Add delay before each attempt
        if (attempt > 1) {
          const delay = 2000 * Math.pow(1.5, attempt - 2);
          console.log(`  Waiting ${Math.round(delay)}ms before retry...`);
          await new Promise(r => setTimeout(r, delay));
        }

        const pasResponse = await httpsRequest('https://riot-geo.pas.si.riotgames.com/pas/v1/service/chat', {
          headers: {
            'Authorization': `Bearer ${accessToken}`,
            'X-Riot-Entitlements-JWT': entitlement,
            'Accept': 'application/json',
            'Content-Type': 'application/json'
          },
          timeout: 60000
        });

        if (pasResponse.statusCode === 200) {
          console.log(`✅ PAS token retrieved successfully on attempt ${attempt}\n`);
          pasSuccess = true;
          break;
        } else {
          console.log(`  Status: ${pasResponse.statusCode}`);
        }
      } catch (error) {
        lastError = error;
        console.log(`  Error: ${error.code || error.message}`);
      }
    }

    if (!pasSuccess) {
      console.error(`❌ Failed to get PAS token after 5 attempts`);
      console.error(`   Last error: ${lastError?.code || lastError?.message}`);
      console.error(`\n⚠️  This indicates a network connectivity issue with Riot servers.`);
      console.error(`   Possible causes:`);
      console.error(`   - Regional network restrictions`);
      console.error(`   - Firewall/antivirus blocking connections`);
      console.error(`   - ISP throttling Riot services`);
      console.error(`   - VPN interference`);
      console.error(`\n   The application will continue to retry automatically.`);
      process.exit(1);
    }

    // Test 5: Summary
    console.log('[5/5] Connection test summary:');
    console.log('✅ All connection tests passed!');
    console.log('✅ XMPP bridge should work correctly\n');

  } catch (error) {
    console.error(`❌ Test failed: ${error.message}`);
    console.error(error.stack);
    process.exit(1);
  }
}

test();

