package com.pessimisticit.scarcity.subscriptions;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import com.pessimisticit.scarcity.resourcemodel.ChangeSubscriber;

import jakarta.annotation.PostConstruct;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    static final String PATH = "/api/subscriptions/ws";

    private final SubscriptionsHandler subscriptionsHandler;
    private final SubscriptionsProperties subscriptionsProperties;

    WebSocketConfig(SubscriptionsHandler subscriptionsHandler, SubscriptionsProperties subscriptionsProperties) {
        this.subscriptionsHandler = subscriptionsHandler;
        this.subscriptionsProperties = subscriptionsProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry areaRegistry) {
        // Any Origin: browsers can't send the JWT in a header, the first frame carries it, and there
        // are no cookies, so a cross-site page gains nothing from connecting.
        areaRegistry.addHandler(subscriptionsHandler, PATH).setAllowedOriginPatterns("*");
    }

    /** A text frame above the limit closes the socket with 1009 (message too big). */
    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean c = new ServletServerContainerFactoryBean();
        c.setMaxTextMessageBufferSize(subscriptionsProperties.getMaxMessageBytes());
        c.setMaxBinaryMessageBufferSize(subscriptionsProperties.getMaxMessageBytes());
        return c;
    }

    /** Wires the LISTEN source to the registry, and runs the periodic digest and ping. */
    @Component
    static class Wiring {
        private final AreaRegistry areaRegistry;
        private final SubscriptionsHandler subscriptionsHandler;
        private final ChangeSubscriber changeSubscriber;

        Wiring(AreaRegistry areaRegistry, SubscriptionsHandler subscriptionsHandler, ChangeSubscriber changeSubscriber) {
            this.areaRegistry = areaRegistry;
            this.subscriptionsHandler = subscriptionsHandler;
            this.changeSubscriber = changeSubscriber;
        }

        @PostConstruct
        void register() {
            changeSubscriber.addListener(areaRegistry::onBatch);
        }

        @Scheduled(initialDelayString = "${scarcity.subscriptions.digest-interval}",
                fixedDelayString = "${scarcity.subscriptions.digest-interval}")
        void digests() {
            areaRegistry.sendDigests();
        }

        @Scheduled(initialDelayString = "${scarcity.subscriptions.ping-interval}",
                fixedDelayString = "${scarcity.subscriptions.ping-interval}")
        void pings() {
            subscriptionsHandler.clients().forEach(Client::ping);
        }
    }
}
