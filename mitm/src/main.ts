import {ConfigMITM} from './ConfigMITM'
import {XmppMITM} from './XmppMITM'
import {getRiotClientPath, isRiotClientRunning} from './riotClientUtils'
import {exec} from 'node:child_process'

// === MITM STABILITY: Global error handlers (ValorantNarrator behavior) ===
// Runtime errors like ECONNRESET, fetch failures must NEVER crash the MITM.
// Only startup-time failures (409, 404, ENOENT) are fatal.

process.on('uncaughtException', (err) => {
    console.log(JSON.stringify({
        type: 'error',
        time: Date.now(),
        code: 500,
        reason: `Uncaught exception: ${err.message}`
    }) + '\n');
    // DO NOT call process.exit - allow MITM to continue
});

process.on('unhandledRejection', (reason: any) => {
    console.log(JSON.stringify({
        type: 'error',
        time: Date.now(),
        code: 500,
        reason: `Unhandled rejection: ${reason}`
    }) + '\n');
    // DO NOT call process.exit - allow MITM to continue
});

(async () => {
    const httpPort = 35479
    const xmppPort = 35478
    const host = '127.0.0.1'

    if (await isRiotClientRunning()) {
        console.log(JSON.stringify({
            type: 'error',
            code: 409,
            reason: 'Riot client is running, please close it before running this tool.'
        }) + '\n')
        process.exit(1)
    }
    const configMitm = new ConfigMITM(httpPort, host, xmppPort)
    await configMitm.start()
    const xmppMitm = new XmppMITM(xmppPort, host, configMitm)
    await xmppMitm.start()
    const riotClientPath = await getRiotClientPath()
    if (riotClientPath == 'Error:404') {
        console.log(JSON.stringify({
            type: 'error',
            code: 404,
            reason: 'Valorant Installation not found. Please install Valorant and try again.'
        }) + '\n')
        process.exit(1)
    }
    if (riotClientPath.startsWith('Error')) {
        console.log(JSON.stringify({
            type: 'error',
            code: 500,
            reason: riotClientPath
        }) + '\n')
        process.exit(1)
    }
    exec(`"${riotClientPath}" --client-config-url="http://${host}:${httpPort}" --launch-product=valorant --launch-patchline=live`)
})()