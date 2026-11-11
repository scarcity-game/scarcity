package com.pessimisticit.scarcity.resourcemodel.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code scarcity.resource-changes.*}. The subscriber only exists when {@code listen=true}.
 * It opens its own connection, built from {@code url/username/password} if set, else taken from the
 * DataSource (Hikari: its jdbcUrl, username and password; any other DataSource: one connection
 * from {@code getConnection()}, kept and never returned).
 */
@ConfigurationProperties("scarcity.resource-changes")
public class ResourceChangesProperties {
    private boolean listen = false;
    private String url;
    private String username;
    private String password;

    public boolean isListen() {
        return listen;
    }

    public void setListen(boolean listen) {
        this.listen = listen;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
