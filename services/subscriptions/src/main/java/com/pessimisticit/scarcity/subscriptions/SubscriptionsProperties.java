package com.pessimisticit.scarcity.subscriptions;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** {@code scarcity.subscriptions.*}; validated at startup. */
@Validated
@ConfigurationProperties("scarcity.subscriptions")
public class SubscriptionsProperties {
    /** Base URL of world (no trailing path). */
    @NotBlank
    private String worldUrl;
    @NotBlank
    private String jwksUri;
    @NotBlank
    private String jwtIssuer;
    /** Close with 4401 if no auth frame arrives within this time of connecting. */
    @NotNull
    private Duration authTimeout;
    @NotNull
    private Duration checksumInterval;
    /** WebSocket-level ping interval (keeps idle sockets alive through proxies). */
    @NotNull
    private Duration pingInterval;
    @Positive
    private int maxMessageBytes;
    @Positive
    private int sendBufferBytes;
    @NotNull
    private Duration sendTimeLimit;
    /** Connect timeout for calls to world. */
    @NotNull
    private Duration worldConnectTimeout;
    /** Read timeout for calls to world. */
    @NotNull
    private Duration worldReadTimeout;

    public SubscriptionsProperties() {
        this.worldUrl = "http://localhost:8080";
        this.jwksUri = "http://localhost:8080/api/auth/.well-known/jwks.json";
        this.jwtIssuer = "scarcity-auth";
        this.authTimeout = Duration.ofSeconds(5);
        this.checksumInterval = Duration.ofSeconds(30);
        this.pingInterval = Duration.ofSeconds(30);
        this.maxMessageBytes = 64 * 1024;
        this.sendBufferBytes = 1024 * 1024;
        this.sendTimeLimit = Duration.ofSeconds(10);
        this.worldConnectTimeout = Duration.ofSeconds(3);
        this.worldReadTimeout = Duration.ofSeconds(10);
    }

    public String getWorldUrl() { return worldUrl; }
    public void setWorldUrl(String v) { this.worldUrl = v; }
    public String getJwksUri() { return jwksUri; }
    public void setJwksUri(String v) { this.jwksUri = v; }
    public String getJwtIssuer() { return jwtIssuer; }
    public void setJwtIssuer(String v) { this.jwtIssuer = v; }
    public Duration getAuthTimeout() { return authTimeout; }
    public void setAuthTimeout(Duration v) { this.authTimeout = v; }
    public Duration getChecksumInterval() { return checksumInterval; }
    public void setChecksumInterval(Duration v) { this.checksumInterval = v; }
    public Duration getPingInterval() { return pingInterval; }
    public void setPingInterval(Duration v) { this.pingInterval = v; }
    public int getMaxMessageBytes() { return maxMessageBytes; }
    public void setMaxMessageBytes(int v) { this.maxMessageBytes = v; }
    public int getSendBufferBytes() { return sendBufferBytes; }
    public void setSendBufferBytes(int v) { this.sendBufferBytes = v; }
    public Duration getWorldConnectTimeout() { return worldConnectTimeout; }
    public void setWorldConnectTimeout(Duration v) { this.worldConnectTimeout = v; }
    public Duration getWorldReadTimeout() { return worldReadTimeout; }
    public void setWorldReadTimeout(Duration v) { this.worldReadTimeout = v; }
    public Duration getSendTimeLimit() { return sendTimeLimit; }
    public void setSendTimeLimit(Duration v) { this.sendTimeLimit = v; }
}
