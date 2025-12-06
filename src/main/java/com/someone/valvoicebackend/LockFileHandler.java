package com.someone.valvoicebackend;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Reads Riot Client's lockfile for connection credentials.
 * Provides: port, password, protocol for local API access.
 * Required to authenticate with Riot's local services.
 */
public class LockFileHandler {
    private String port;
    private String password;
    private String protocol;
    private String pid;
    private String name;
    private String path;

    /**
     * Reads and parses the lockfile at the given path.
     * Riot lockfile format: name:pid:port:password:protocol
     */
    public boolean readLockFile(String lockFilePath) {
        File file = new File(lockFilePath);
        if (!file.exists()) return false;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            if (line == null) return false;
            String[] parts = line.split(":");
            if (parts.length != 5) return false;
            name = parts[0];
            pid = parts[1];
            port = parts[2];
            password = parts[3];
            protocol = parts[4];
            path = lockFilePath;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Attempt to locate the Riot lockfile in common Windows install locations.
     * @return absolute path if found, else null.
     */
    public static String findDefaultLockfile() {
        String localAppData = System.getenv("LOCALAPPDATA");
        String programData = System.getenv("PROGRAMDATA");
        List<String> candidates = Arrays.asList(
                localAppData + File.separator + "Riot Games" + File.separator + "Riot Client" + File.separator + "Config" + File.separator + "lockfile",
                "C:" + File.separator + "Riot Games" + File.separator + "Riot Client" + File.separator + "Config" + File.separator + "lockfile",
                programData + File.separator + "Riot Games" + File.separator + "Riot Client" + File.separator + "Config" + File.separator + "lockfile"
        );
        for (String c : candidates) {
            if (c != null) {
                File f = new File(c);
                if (f.exists()) return f.getAbsolutePath();
            }
        }
        return null;
    }

    public String getPort() { return port; }
    public String getPassword() { return password; }
    public String getProtocol() { return protocol; }
    public String getPid() { return pid; }
    public String getName() { return name; }
    public String getPath() { return path; }
}
