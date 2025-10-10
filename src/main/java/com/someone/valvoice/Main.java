package com.someone.valvoice;

import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static final String APP_NAME = "ValVoice";
    public static final String CONFIG_DIR = Paths.get(System.getenv("APPDATA"), APP_NAME).toString();
    public static final String LOCK_FILE_NAME = "lockFile";
    public static final String LOCK_FILE = Paths.get(CONFIG_DIR, LOCK_FILE_NAME).toString();
    public static double currentVersion;
    private static Properties properties;

    // Node.js XMPP process management
    private static Process xmppNodeProcess;
    private static ExecutorService xmppIoPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "xmpp-node-io");
        t.setDaemon(true);
        return t;
    });

    private static final Pattern IQ_ID_PATTERN = Pattern.compile("<iq[^>]*id=([\"\'])_xmpp_bind1\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern JID_TAG_PATTERN = Pattern.compile("<jid>(.*?)</jid>", Pattern.CASE_INSENSITIVE);
    private static final Pattern MESSAGE_START_PATTERN = Pattern.compile("^<message", Pattern.CASE_INSENSITIVE);
    private static final Pattern IQ_START_PATTERN = Pattern.compile("^<iq", Pattern.CASE_INSENSITIVE);
    // External bridge name (only this is supported)
    private static final String XMPP_EXE_NAME_PRIMARY = "valvoice-xmpp.exe";

    private static boolean lockInstance() {
        try {
            Files.createDirectories(Path.of(CONFIG_DIR));
            File file = new File(LOCK_FILE);
            file.createNewFile();
            RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
            FileLock lock = randomAccessFile.getChannel().tryLock();

            if (lock == null) {
                randomAccessFile.close();
                ValVoiceApplication.showPreStartupDialog(
                        APP_NAME,
                        "Another instance of this application is already running!",
                        MessageType.WARNING
                );
                System.exit(0);
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    lock.release();
                    randomAccessFile.close();
                    Files.deleteIfExists(Paths.get(LOCK_FILE));
                } catch (IOException e) {
                    logger.error("Error releasing lock", e);
                }
            }));
        } catch (IOException e) {
            logger.error("Error creating lock file", e);
        }
        return true;
    }

    private static void startXmppNodeProcess() {
        if (xmppNodeProcess != null && xmppNodeProcess.isAlive()) {
            logger.warn("Xmpp-Node process already running");
            return;
        }

        Path workingDir = Paths.get(System.getProperty("user.dir"));
        Path exePrimary = workingDir.resolve(XMPP_EXE_NAME_PRIMARY);
        Path exeCandidate = Files.isRegularFile(exePrimary) ? exePrimary : null;

        // If no exe is present, try to build valvoice-xmpp.exe automatically (convenience)
        if (exeCandidate == null) {
            Path built = tryBuildBridgeExecutable(workingDir);
            if (built != null && Files.isRegularFile(built)) {
                exeCandidate = built;
            }
        }
        boolean useExternalExe = exeCandidate != null && Files.isReadable(exeCandidate);

        Path tempScriptPath = null; // only used when falling back to script
        ProcessBuilder pb;
        if (useExternalExe) {
            logger.info("Using external XMPP bridge executable: {}", exeCandidate.toAbsolutePath());
            pb = new ProcessBuilder(exeCandidate.toAbsolutePath().toString());
        } else {
            logger.info("External {} not found; falling back to embedded xmpp-node.js stub", XMPP_EXE_NAME_PRIMARY);
            String resourceName = "/com/someone/valvoice/xmpp-node.js";
            try (InputStream in = Main.class.getResourceAsStream(resourceName)) {
                if (in == null) {
                    logger.error("Could not find resource {} (XMPP bridge unavailable)", resourceName);
                    return;
                }
                byte[] bytes = in.readAllBytes();
                tempScriptPath = Files.createTempFile("xmpp-node-", ".js");
                Files.write(tempScriptPath, bytes);
            } catch (IOException e) {
                logger.error("Failed to extract xmpp-node.js", e);
                return;
            }
            String nodeCommand = "node"; // Assumes Node.js is on PATH
            // Verify Node.js availability
            try {
                Process ver = new ProcessBuilder(nodeCommand, "--version").redirectErrorStream(true).start();
                if (!ver.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    ver.destroyForcibly();
                    logger.error("Node.js availability check timed out; cannot run embedded XMPP stub.");
                    System.setProperty("valvoice.nodeAvailable", "false");
                    return;
                }
                int code = ver.exitValue();
                if (code != 0) {
                    logger.error("Node.js not available (exit code {}). Install Node.js or provide {}.", code, XMPP_EXE_NAME_PRIMARY);
                    System.setProperty("valvoice.nodeAvailable", "false");
                    return;
                }
                System.setProperty("valvoice.nodeAvailable", "true");
            } catch (Exception ex) {
                logger.error("Node.js not found on PATH. Install Node.js or place {} next to the JAR.", XMPP_EXE_NAME_PRIMARY, ex);
                System.setProperty("valvoice.nodeAvailable", "false");
                return;
            }
            pb = new ProcessBuilder(nodeCommand, tempScriptPath.toAbsolutePath().toString());
        }

        pb.redirectErrorStream(true);
        pb.directory(workingDir.toFile());
        try {
            xmppNodeProcess = pb.start();
            String mode = useExternalExe ? "external-exe" : "embedded-script";
            System.setProperty("valvoice.bridgeMode", mode);
            if (useExternalExe) {
                System.setProperty("valvoice.nodeAvailable", System.getProperty("valvoice.nodeAvailable", "n/a"));
            }
            logger.info("Started XMPP bridge (mode: {})", mode);
        } catch (IOException e) {
            logger.error("Failed to start XMPP bridge process", e);
            return;
        }

        final Path scriptRef = tempScriptPath; // may be null if external exe used
        xmppIoPool.submit(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(xmppNodeProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonElement parsed;
                    try {
                        parsed = JsonParser.parseString(line);
                        if (!parsed.isJsonObject()) {
                            logger.debug("[XmppBridge raw] {}", line);
                            continue;
                        }
                        JsonObject obj = parsed.getAsJsonObject();
                        String type = obj.has("type") && !obj.get("type").isJsonNull() ? obj.get("type").getAsString() : "";
                        switch (type) {
                            case "incoming" -> handleIncomingStanza(obj);
                            case "error" -> {
                                String err = obj.has("error") ? obj.get("error").getAsString() : "(unknown)";
                                logger.warn("XMPP error event: {}", err);
                            }
                            case "close-riot" -> logger.info("Riot client closed event received");
                            case "close-valorant" -> logger.info("Valorant closed event received");
                            case "startup", "heartbeat", "shutdown" -> logger.debug("[XmppBridge:{}] {}", type, obj);
                            default -> logger.debug("[XmppBridge:?] {}", obj);
                        }
                    } catch (Exception ex) {
                        logger.info("[XmppBridge log] {}", line);
                    }
                }
            } catch (IOException e) {
                logger.warn("XMPP bridge stdout reader terminating", e);
            } finally {
                logger.info("XMPP bridge output closed ({})", useExternalExe ? "external-exe" : ("script=" + scriptRef));
            }
        });

        xmppIoPool.submit(() -> {
            try {
                int code = xmppNodeProcess.waitFor();
                logger.warn("XMPP bridge process exited with code {}", code);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (xmppNodeProcess != null && xmppNodeProcess.isAlive()) {
                logger.info("Destroying XMPP bridge process");
                xmppNodeProcess.destroy();
                try { xmppNodeProcess.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, "xmpp-bridge-shutdown"));
    }

    // Attempt to build valvoice-xmpp.exe using the included xmpp-bridge project.
    // Returns path to built exe if successful, else null.
    private static Path tryBuildBridgeExecutable(Path workingDir) {
        try {
            Path bridgeDir = workingDir.resolve("xmpp-bridge");
            Path pkgJson = bridgeDir.resolve("package.json");
            if (!Files.isRegularFile(pkgJson)) {
                logger.info("xmpp-bridge folder not found ({}); skipping auto-build.", bridgeDir.toAbsolutePath());
                return null;
            }
            if (!isCommandAvailable("node") || !isCommandAvailable("npm")) {
                logger.info("Node.js or npm not available; cannot auto-build valvoice-xmpp.exe.");
                return null;
            }
            logger.info("Auto-building valvoice-xmpp.exe (this may take a minute on first run)...");
            // npm install
            int npmInstall = runAndWait(new ProcessBuilder("cmd.exe", "/c", "npm install").directory(bridgeDir.toFile()), 10, TimeUnit.MINUTES);
            if (npmInstall != 0) {
                logger.warn("npm install failed with exit code {}", npmInstall);
                return null;
            }
            // npm run build:exe
            int npmBuild = runAndWait(new ProcessBuilder("cmd.exe", "/c", "npm run build:exe").directory(bridgeDir.toFile()), 10, TimeUnit.MINUTES);
            if (npmBuild != 0) {
                logger.warn("npm run build:exe failed with exit code {}", npmBuild);
                return null;
            }
            Path exe = workingDir.resolve(XMPP_EXE_NAME_PRIMARY);
            if (Files.isRegularFile(exe)) {
                logger.info("Successfully built {}", exe.toAbsolutePath());
                return exe;
            } else {
                logger.warn("Auto-build completed but {} not found", exe.toAbsolutePath());
                return null;
            }
        } catch (Exception e) {
            logger.warn("Auto-build of valvoice-xmpp.exe failed", e);
            return null;
        }
    }

    private static boolean isCommandAvailable(String command) {
        try {
            Process p = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (!ok) p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private static int runAndWait(ProcessBuilder pb, long timeout, TimeUnit unit) throws IOException, InterruptedException {
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        // Stream output to log (briefly)
        Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "proc-log"); t.setDaemon(true); return t; })
                .submit(() -> {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line; while ((line = br.readLine()) != null) logger.info("[bridge-build] {}", line);
                    } catch (IOException ignored) {}
                });
        if (!proc.waitFor(timeout, unit)) {
            proc.destroyForcibly();
            logger.warn("Process timed out: {}", String.join(" ", pb.command()));
            return -1;
        }
        return proc.exitValue();
    }

    private static void handleIncomingStanza(JsonObject obj) {
        if (!obj.has("data") || obj.get("data").isJsonNull()) return;
        String xml = obj.get("data").getAsString();
        if (xml == null || xml.isBlank()) return;
        try {
            if (MESSAGE_START_PATTERN.matcher(xml).find()) {
                Message msg = new Message(xml);
                logger.info("Received message: {}", msg);
                ChatDataHandler.getInstance().message(msg);
            } else if (IQ_START_PATTERN.matcher(xml).find()) {
                detectSelfIdFromIq(xml);
            } else {
                // presence/other stanzas ignored currently
            }
        } catch (Exception e) {
            logger.debug("Failed handling incoming stanza", e);
        }
    }

    private static void detectSelfIdFromIq(String xml) {
        try {
            Matcher idMatcher = IQ_ID_PATTERN.matcher(xml);
            if (!idMatcher.find()) return; // not the bind response
            Matcher jidMatcher = JID_TAG_PATTERN.matcher(xml);
            if (jidMatcher.find()) {
                String fullJid = jidMatcher.group(1);
                if (fullJid != null && fullJid.contains("@")) {
                    String userPart = fullJid.substring(0, fullJid.indexOf('@'));
                    if (!userPart.isBlank()) {
                        ChatDataHandler.getInstance().updateSelfId(userPart);
                        logger.info("Self ID (bind) detected: {}", userPart);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to parse bind iq for self ID", e);
        }
    }

    public static void main(String[] args) {
        logger.info("Starting {} Application", APP_NAME);
        try (InputStream inputStream = Main.class.getResourceAsStream("config.properties")) {
            if (inputStream == null) {
                throw new FileNotFoundException("config.properties not found on classpath (expected in same package)");
            }
            properties = new Properties();
            properties.load(inputStream);
            currentVersion = Double.parseDouble(properties.getProperty("version", "0.0"));
            logger.info("Version: {}", currentVersion);
            logger.info("Build date: {}", properties.getProperty("buildTimestamp", "unknown"));
        } catch (IOException | NumberFormatException e) {
            logger.error("CRITICAL: Could not load config properties!", e);
            ValVoiceApplication.showPreStartupDialog(
                    "Configuration Error",
                    "Could not load application configuration. Please reinstall.",
                    MessageType.ERROR
            );
            System.exit(-1);
        }

        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));
            logger.info("Config directory: {}", CONFIG_DIR);
        } catch (IOException e) {
            logger.error("Could not create config directory", e);
        }

        lockInstance();
        startXmppNodeProcess();

        logger.info("Launching JavaFX Application");
        Application.launch(ValVoiceApplication.class, args);
    }
}
