package com.pessimisticit.scarcity.resourcemodel.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.resourcemodel.api.ResourceController;

/** Registers the REST controller (prefix: {@code scarcity.resource-api.prefix}) in servlet apps with a store. */
@AutoConfiguration(after = ResourceModelAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnBean(ResourceStore.class)
@EnableConfigurationProperties(ResourceApiProperties.class)
public class ResourceApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ResourceController resourceController(ResourceStore store) {
        return new ResourceController(store);
    }
}
