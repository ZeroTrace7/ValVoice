package com.someone.valvoicebackend;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for checking Riot Client status and locating installation path.
 * Used by MITM proxy to verify client state before launching hijacked client.
 */
public class RiotClientUtils {

    private static final Pattern INSTALL_STRING_REGEX = Pattern.compile(
        "    UninstallString    REG_SZ    \"(.+?)\" --uninstall-product=valorant --uninstall-patchline=live"
    );

    public static CompletableFuture<Boolean> isRiotClientRunning() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Process process = Runtime.getRuntime().exec(
                    "tasklist /fi \"imagename eq RiotClientServices.exe\""
                );

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
                );

                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }

                process.waitFor();
                return output.toString().contains("RiotClientServices.exe");

            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        });
    }

    public static CompletableFuture<String> getRiotClientPath() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Process process = Runtime.getRuntime().exec(
                    "reg query \"HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Riot Game valorant.live\" /v UninstallString"
                );

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
                );

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("ERROR")) {
                        return "Error:" + line;
                    }

                    Matcher matcher = INSTALL_STRING_REGEX.matcher(line);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }

                process.waitFor();
                return "Error:404";

            } catch (Exception e) {
                e.printStackTrace();
                return "Error:" + e.getMessage();
            }
        });
    }
}

