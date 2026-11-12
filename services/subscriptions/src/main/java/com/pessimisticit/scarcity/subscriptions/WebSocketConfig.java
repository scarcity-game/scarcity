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

    private final SubscriptionsHandler handler;
    private final SubscriptionsProperties props;

    WebSocketConfig(SubscriptionsHandler handler, SubscriptionsProperties props) {
        this.handler = handler;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Any Origin: browsers can't send the JWT in a header, the first frame carries it, and there
        // are no cookies, so a cross-site page gains nothing from connecting.
        registry.addHandler(handler, PATH).setAllowedOriginPatterns("*");
    }

    /** A text frame above the limit closes the socket with 1009 (message too big). */
    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean c = new ServletServerContainerFactoryBean();
        c.setMaxTextMessageBufferSize(props.getMaxMessageBytes());
        c.setMaxBinaryMessageBufferSize(props.getMaxMessageBytes());
        return c;
    }

    /** Wires the LISTEN source to the registry, and runs the periodic digest and ping. */
    @Component
    static class Wiring {
        private final AreaRegistry registry;
        private final SubscriptionsHandler handler;
        private final ChangeSubscriber subscriber;

        Wiring(AreaRegistry registry, SubscriptionsHandler handler, ChangeSubscriber subscriber) {
            this.registry = registry;
            this.handler = handler;
            this.subscriber = subscriber;
        }

        @PostConstruct
        void register() {
            subscriber.addListener(registry::onBatch);
        }

        @Scheduled(initialDelayString = "${scarcity.subscriptions.digest-interval}",
                fixedDelayString = "${scarcity.subscriptions.digest-interval}")
        void digests() {
            registry.sendDigests();
        }

        @Scheduled(initialDelayString = "${scarcity.subscriptions.ping-interval}",
                fixedDelayString = "${scarcity.subscriptions.ping-interval}")
        void pings() {
            handler.clients().forEach(Client::ping);
        }
    }
}
