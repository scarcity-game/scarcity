package com.pessimisticit.scarcity.resourcemodel.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code scarcity.resource-api.prefix}: where the controller is mounted (default {@code /resources}).
 * The controller reads the property directly via a placeholder; this class documents/binds it.
 */
@ConfigurationProperties("scarcity.resource-api")
public class ResourceApiProperties {
    private String prefix = "/resources";

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }
}
