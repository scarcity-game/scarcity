package com.pessimisticit.scarcity.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import tools.jackson.databind.ObjectMapper;

class DrainTest {

    final SubscriptionsProperties subscriptionsProperties;
    final SubscriptionsHandler subscriptionsHandler;
    final List<Object> events;
    final Drain drain;

    DrainTest() {
        this.subscriptionsProperties = new SubscriptionsProperties();
        subscriptionsProperties.setDrainDelay(Duration.ofMillis(10));
        subscriptionsProperties.setDrainWindow(Duration.ofMillis(30));
        this.subscriptionsHandler = new SubscriptionsHandler(subscriptionsProperties, null, null, null,
                mock(TaskScheduler.class), new ObjectMapper());
        this.events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        this.drain = new Drain(subscriptionsHandler, subscriptionsProperties, publisher);
    }

    static WebSocketSession session(String id, String uri) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.getUri()).thenReturn(URI.create(uri));
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void stoppingRefusesTrafficThenClosesEverySocketWith1012() throws Exception {
        WebSocketSession a = session("a", "ws://h/api/subscriptions/ws?area=area:x");
        WebSocketSession b = session("b", "ws://h/api/subscriptions/ws");
        subscriptionsHandler.afterConnectionEstablished(a);
        subscriptionsHandler.afterConnectionEstablished(b);
        drain.start();

        drain.stop();

        assertThat(drain.isRunning()).isFalse();
        assertThat(events).singleElement().satisfies(e -> assertThat(((AvailabilityChangeEvent<?>) e).getState())
                .isEqualTo(ReadinessState.REFUSING_TRAFFIC));
        for (WebSocketSession s : List.of(a, b)) {
            ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
            verify(s).close(status.capture());
            assertThat(status.getValue().getCode()).isEqualTo(1012);
        }
    }

    @Test
    void withNoSocketOpenItOnlyRefusesTraffic() {
        subscriptionsProperties.setDrainDelay(Duration.ofHours(1));
        drain.stop();
        assertThat(events).hasSize(1);
    }

    @Test
    void theSocketsAreaComesFromTheConnectUrl() {
        assertThat(Client.socketArea(session("a", "ws://h/ws?area=area%3Ax"))).isEqualTo("area:x");
        assertThat(Client.socketArea(session("b", "ws://h/ws?area="))).isNull();
        assertThat(Client.socketArea(session("c", "ws://h/ws"))).isNull();
    }

    @Test
    void itStopsBeforeTheWebServer() {
        assertThat(drain.getPhase()).isEqualTo(Integer.MAX_VALUE);
        assertThat(drain.isAutoStartup()).isTrue();
    }
}
