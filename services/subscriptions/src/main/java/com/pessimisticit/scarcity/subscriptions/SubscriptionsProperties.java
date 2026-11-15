package com.pessimisticit.scarcity.subscriptions;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code scarcity.subscriptions.*}. */
@ConfigurationProperties("scarcity.subscriptions")
public class SubscriptionsProperties {
    /** Base URL of world (no trailing path). */
    private String worldUrl;
    private String jwksUri;
    private String jwtIssuer;
    /** Close with 4401 if no auth frame arrives within this time of connecting. */
    private Duration authTimeout;
    private Duration digestInterval;
    /** WebSocket-level ping interval (keeps idle sockets alive through proxies). */
    private Duration pingInterval;
    private int maxMessageBytes;
    private int sendBufferBytes;
    private Duration sendTimeLimit;

    public SubscriptionsProperties() {
        this.worldUrl = "http://localhost:8080";
        this.jwksUri = "http://localhost:8080/api/auth/.well-known/jwks.json";
        this.jwtIssuer = "scarcity-auth";
        this.authTimeout = Duration.ofSeconds(5);
        this.digestInterval = Duration.ofSeconds(30);
        this.pingInterval = Duration.ofSeconds(30);
        this.maxMessageBytes = 64 * 1024;
        this.sendBufferBytes = 1024 * 1024;
        this.sendTimeLimit = Duration.ofSeconds(10);
    }

    public String getWorldUrl() { return worldUrl; }
    public void setWorldUrl(String v) { this.worldUrl = v; }
    public String getJwksUri() { return jwksUri; }
    public void setJwksUri(String v) { this.jwksUri = v; }
    public String getJwtIssuer() { return jwtIssuer; }
    public void setJwtIssuer(String v) { this.jwtIssuer = v; }
    public Duration getAuthTimeout() { return authTimeout; }
    public void setAuthTimeout(Duration v) { this.authTimeout = v; }
    public Duration getDigestInterval() { return digestInterval; }
    public void setDigestInterval(Duration v) { this.digestInterval = v; }
    public Duration getPingInterval() { return pingInterval; }
    public void setPingInterval(Duration v) { this.pingInterval = v; }
    public int getMaxMessageBytes() { return maxMessageBytes; }
    public void setMaxMessageBytes(int v) { this.maxMessageBytes = v; }
    public int getSendBufferBytes() { return sendBufferBytes; }
    public void setSendBufferBytes(int v) { this.sendBufferBytes = v; }
    public Duration getSendTimeLimit() { return sendTimeLimit; }
    public void setSendTimeLimit(Duration v) { this.sendTimeLimit = v; }
}
