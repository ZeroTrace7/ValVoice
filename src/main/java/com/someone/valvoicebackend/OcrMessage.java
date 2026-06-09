package com.someone.valvoicebackend;

/**
 * OcrMessage - DTO record for OCR-sourced chat events.
 *
 * Produced by ValVoiceOCR.exe (C# sidecar) and deserialized from its stdout JSON stream
 * by OcrChatClient. Dispatched to ChatDataHandler.handleOcrMessage() for TTS routing.
 *
 * No XMPP fields. No PUUID. No JID. No XML.
 * All fields are from the JSON chat event: { "type":"chat", "channel":"TEAM", ... }
 *
 * Phase 2.2: ownMessage is evaluated by OcrChatClient using display-name comparison
 * (selfName.equalsIgnoreCase(name)) before dispatch, following VN's OcrChatClient pattern.
 *
 * @param channel    Chat channel: "PARTY", "TEAM", or "ALL"
 * @param name       Sender display name as read by OCR (may include OCR noise)
 * @param body       Message text content, max 300 chars, already cleaned by sidecar
 * @param timestamp  UTC epoch milliseconds when the sidecar captured this message
 * @param ownMessage true if sender display name matches local player's display name
 */
public record OcrMessage(
        String  channel,
        String  name,
        String  body,
        long    timestamp,
        boolean ownMessage
) {}
