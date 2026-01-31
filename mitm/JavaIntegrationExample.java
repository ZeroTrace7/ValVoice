// ========================================
// NEW Main.java - MITM Integration
// ========================================
// Replace your current Main.java bridge launching code with this

import java.io.*;
import java.util.concurrent.*;

public class Main {
    
    private static Process mitmProcess;
    private static ExecutorService xmppIoPool;
    
    public static void main(String[] args) {
        try {
            // STEP 1: Kill Riot if running (MITM needs to launch it)
            killRiotClient();
            Thread.sleep(2000); // Wait for clean shutdown
            
            // STEP 2: Start MITM proxy
            startMitmProxy();
            
            // STEP 3: Read XMPP traffic from MITM's STDOUT
            readXmppTraffic();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Kill Riot client if running
     * MITM must launch Riot itself with custom config URL
     */
    private static void killRiotClient() throws IOException {
        System.out.println("Checking for existing Riot client...");
        Runtime.getRuntime().exec("taskkill /F /IM RiotClientServices.exe");
    }
    
    /**
     * Launch the MITM proxy
     * This will start ConfigMITM, XmppMITM, and launch Riot client
     */
    private static void startMitmProxy() throws IOException {
        System.out.println("Starting MITM proxy...");
        
        // Option 1: Run compiled executable
        ProcessBuilder pb = new ProcessBuilder("mitm/target/valvoice-xmpp.exe");
        
        // Option 2: Run with Node.js directly (for development)
        // ProcessBuilder pb = new ProcessBuilder("node", "mitm/dist/main.js");
        
        pb.redirectErrorStream(true); // Merge stderr into stdout
        mitmProcess = pb.start();
        
        System.out.println("MITM proxy started (PID: " + mitmProcess.pid() + ")");
    }
    
    /**
     * Read XMPP traffic from MITM's STDOUT
     * The MITM outputs JSON lines with XMPP data
     */
    private static void readXmppTraffic() {
        xmppIoPool = Executors.newSingleThreadExecutor();
        
        xmppIoPool.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(mitmProcess.getInputStream()))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    handleMitmOutput(line);
                }
                
            } catch (IOException e) {
                System.err.println("Error reading MITM output: " + e.getMessage());
            }
        });
    }
    
    /**
     * Parse JSON output from MITM and extract XMPP messages
     * 
     * JSON format:
     * {"type":"incoming","time":1234567890,"data":"<message>...</message>"}
     * {"type":"outgoing","time":1234567890,"data":"<iq>...</iq>"}
     * {"type":"error","code":409,"reason":"..."}
     */
    private static void handleMitmOutput(String jsonLine) {
        try {
            // Simple JSON parsing (use a proper JSON library in production)
            if (jsonLine.contains("\"type\":\"incoming\"")) {
                // Extract XMPP data
                String xmlData = extractXmlData(jsonLine);
                if (xmlData != null && !xmlData.isEmpty()) {
                    handleIncomingStanza(xmlData);
                }
            } 
            else if (jsonLine.contains("\"type\":\"outgoing\"")) {
                // Optional: Log outgoing traffic
                String xmlData = extractXmlData(jsonLine);
                System.out.println("OUT: " + xmlData);
            }
            else if (jsonLine.contains("\"type\":\"error\"")) {
                System.err.println("MITM Error: " + jsonLine);
            }
            else if (jsonLine.contains("\"type\":\"open-valorant\"")) {
                System.out.println("Riot client connected to MITM");
            }
            else if (jsonLine.contains("\"type\":\"open-riot\"")) {
                System.out.println("MITM connected to Riot server");
            }
            
        } catch (Exception e) {
            System.err.println("Error parsing MITM output: " + e.getMessage());
        }
    }
    
    /**
     * Extract XML data from JSON line
     * Input: {"type":"incoming","time":1234567890,"data":"<message>...</message>"}
     * Output: <message>...</message>
     */
    private static String extractXmlData(String jsonLine) {
        int dataStart = jsonLine.indexOf("\"data\":\"") + 8;
        int dataEnd = jsonLine.lastIndexOf("\"");
        
        if (dataStart > 8 && dataEnd > dataStart) {
            String data = jsonLine.substring(dataStart, dataEnd);
            // Unescape JSON string
            return data.replace("\\\"", "\"")
                      .replace("\\\\", "\\")
                      .replace("\\n", "\n")
                      .replace("\\r", "\r")
                      .replace("\\t", "\t");
        }
        return null;
    }
    
    /**
     * Handle incoming XMPP stanza (reuse your existing logic)
     * This is where your Message.java parsing happens
     */
    private static void handleIncomingStanza(String xml) {
        System.out.println("IN: " + xml);
        
        // YOUR EXISTING CODE HERE
        // Parse XML with Message.java
        // Extract chat messages
        // Trigger TTS with InbuiltVoiceSynthesizer
        
        // Example:
        // Message msg = Message.fromXml(xml);
        // if (msg.isGroupChat()) {
        //     ChatDataHandler.handleTeamMessage(msg);
        //     InbuiltVoiceSynthesizer.speak(msg.getBody());
        // }
    }
    
    /**
     * Cleanup on shutdown
     */
    public static void shutdown() {
        if (mitmProcess != null && mitmProcess.isAlive()) {
            mitmProcess.destroy();
        }
        if (xmppIoPool != null) {
            xmppIoPool.shutdown();
        }
    }
}
