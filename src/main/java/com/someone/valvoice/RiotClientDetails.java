package com.someone.valvoice;

import java.time.Instant;
import java.util.Objects;

/**
 * Holds cached Riot client context required for authenticated / region-aware requests.
 * Obtained via local client endpoints (e.g. /chat/v1/session and product session endpoints).
 */
public class RiotClientDetails {
    private final String subjectId; // PUUID
    private final String region;    // e.g. na, eu, ap
    private final String clientVersion; // e.g. release-09.05-shipping-xxxxx
    private final Instant fetchedAt;

    public RiotClientDetails(String subjectId, String region, String clientVersion, Instant fetchedAt) {
        this.subjectId = subjectId;
        this.region = region;
        this.clientVersion = clientVersion;
        this.fetchedAt = fetchedAt == null ? Instant.now() : fetchedAt;
    }

    public String getSubjectId() { return subjectId; }
    public String getRegion() { return region; }
    public String getClientVersion() { return clientVersion; }
    public Instant getFetchedAt() { return fetchedAt; }

    public boolean isStale(long maxAgeSeconds) {
        return fetchedAt.plusSeconds(maxAgeSeconds).isBefore(Instant.now());
    }

    @Override
    public String toString() {
        return "RiotClientDetails{" +
                "subjectId='" + subjectId + '\'' +
                ", region='" + region + '\'' +
                ", clientVersion='" + clientVersion + '\'' +
                ", fetchedAt=" + fetchedAt +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RiotClientDetails)) return false;
        RiotClientDetails that = (RiotClientDetails) o;
        return Objects.equals(subjectId, that.subjectId) && Objects.equals(region, that.region) && Objects.equals(clientVersion, that.clientVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subjectId, region, clientVersion);
    }
}

