package com.someone.valvoice;

/**
 * Holds chat related runtime properties. In a fuller implementation these
 * could be loaded from secure storage, authentication flow, or Valorant client APIs.
 */
public class ChatProperties {
    private String selfID = ""; // Riot PUUID or chat user id (portion before @)

    public String getSelfID() {
        return selfID;
    }

    public void setSelfID(String selfID) {
        this.selfID = selfID == null ? "" : selfID.trim();
    }
}

