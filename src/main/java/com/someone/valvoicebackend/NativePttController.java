package com.someone.valvoicebackend;

import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

/**
 * Native Windows Push-To-Talk controller backed by user32.dll SendInput.
 */
public final class NativePttController {

    private static final int INPUT_KEYBOARD = 1;
    private static final int KEYEVENTF_KEYUP = 0x0002;

    private NativePttController() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    public static void pressPtt(int vkCode) {
        sendKeyboardInput(vkCode, false);
    }

    public static void releasePtt(int vkCode) {
        sendKeyboardInput(vkCode, true);
    }

    private static void sendKeyboardInput(int vkCode, boolean keyUp) {
        WinUser.INPUT input = new WinUser.INPUT();
        input.type = new WinDef.DWORD(INPUT_KEYBOARD);
        input.input.setType("ki");
        input.input.ki = new WinUser.KEYBDINPUT();
        input.input.ki.wVk = new WinDef.WORD(vkCode);
        input.input.ki.wScan = new WinDef.WORD(0);
        input.input.ki.dwFlags = new WinDef.DWORD(keyUp ? KEYEVENTF_KEYUP : 0);
        input.input.ki.time = new WinDef.DWORD(0);
        input.input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(0);

        WinDef.DWORD sent = User32.INSTANCE.SendInput(new WinDef.DWORD(1), (WinUser.INPUT[]) input.toArray(1), input.size());
        if (sent.intValue() != 1) {
            throw new IllegalStateException("SendInput failed for vkCode=" + vkCode + ", keyUp=" + keyUp);
        }
    }
}
