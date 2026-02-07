package com.someone.valvoicebackend;

/**
 * ParsedMessage - Data Transfer Object for StAX-parsed XMPP message fields.
 *
 * Phase 2B: Production Cutover to StAX-based parsing.
 * This DTO carries extracted fields from XmppStreamParser to business logic,
 * eliminating the need for regex-based extraction in ValVoiceBackend.
 *
 * ValorantNarrator Reference: Clean separation between parsing and business logic.
 */
public class ParsedMessage {
    private final String from;      // 'from' attribute - sender JID
    private final String to;        // 'to' attribute - recipient JID
    private final String id;        // 'id' attribute - message ID for deduplication
    private final String type;      // 'type' attribute - 'chat', 'groupchat', etc.
    private final String stamp;     // 'stamp' attribute - timestamp for historical detection
    private final String body;      // <body> element content
    private final String jid;       // 'jid' attribute (if present)
    private final String rawXml;    // Original XML for Message constructor

    private ParsedMessage(Builder builder) {
        this.from = builder.from;
        this.to = builder.to;
        this.id = builder.id;
        this.type = builder.type;
        this.stamp = builder.stamp;
        this.body = builder.body;
        this.jid = builder.jid;
        this.rawXml = builder.rawXml;
    }

    // Getters
    public String getFrom() { return from; }
    public String getTo() { return to; }
    public String getId() { return id; }
    public String getType() { return type; }
    public String getStamp() { return stamp; }
    public String getBody() { return body; }
    public String getJid() { return jid; }
    public String getRawXml() { return rawXml; }

    // Convenience methods
    public boolean hasBody() {
        return body != null && !body.isEmpty();
    }

    public boolean hasStamp() {
        return stamp != null && !stamp.isEmpty();
    }

    public boolean hasId() {
        return id != null && !id.isEmpty();
    }

    @Override
    public String toString() {
        return "ParsedMessage{" +
                "from='" + (from != null ? from.substring(0, Math.min(30, from.length())) : "null") + "'" +
                ", type='" + type + "'" +
                ", id='" + (id != null ? id.substring(0, Math.min(20, id.length())) : "null") + "'" +
                ", stamp='" + stamp + "'" +
                ", body='" + (body != null ? (body.length() > 30 ? body.substring(0, 27) + "..." : body) : "null") + "'" +
                '}';
    }

    /**
     * Builder pattern for constructing ParsedMessage instances.
     */
    public static class Builder {
        private String from;
        private String to;
        private String id;
        private String type;
        private String stamp;
        private String body;
        private String jid;
        private String rawXml;

        public Builder from(String from) { this.from = from; return this; }
        public Builder to(String to) { this.to = to; return this; }
        public Builder id(String id) { this.id = id; return this; }
        public Builder type(String type) { this.type = type; return this; }
        public Builder stamp(String stamp) { this.stamp = stamp; return this; }
        public Builder body(String body) { this.body = body; return this; }
        public Builder jid(String jid) { this.jid = jid; return this; }
        public Builder rawXml(String rawXml) { this.rawXml = rawXml; return this; }

        public ParsedMessage build() {
            return new ParsedMessage(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
