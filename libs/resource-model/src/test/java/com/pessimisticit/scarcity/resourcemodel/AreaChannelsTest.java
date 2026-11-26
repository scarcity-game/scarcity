package com.pessimisticit.scarcity.resourcemodel;

import static com.pessimisticit.scarcity.resourcemodel.Changes.batch;
import static com.pessimisticit.scarcity.resourcemodel.Changes.change;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.pessimisticit.scarcity.world.model.ResourceKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.node.ObjectNode;

/** One channel per area, LISTENed only while wanted, and the turn notices every subscriber gets (#378). */
class AreaChannelsTest extends AbstractDbTest {

    private final BlockingQueue<ChangeBatch> received = new LinkedBlockingQueue<>();
    private final BlockingQueue<TurnNotice> notices = new LinkedBlockingQueue<>();

    @BeforeEach
    void listen() throws Exception {
        subscriber.addListener(received::add);
        subscriber.addTurnListener(notices::add);
        assertThat(subscriber.awaitListening(Duration.ofSeconds(15))).isTrue();
    }

    @Test
    void aUuidAreaGetsItsHexAndAnyOtherIdAHash() {
        assertThat(Channels.area("area:7f3c0000-0000-4000-8000-00000000000a"))
                .isEqualTo("area_7f3c0000000040008000" + "00000000000a");
        assertThat(Channels.area("area:1")).matches("area_[0-9a-f]{32}").isNotEqualTo(Channels.area("area:2"));
        assertThat(Channels.valid(Channels.area("area:x'; DROP TABLE resources;--"))).isTrue();
        assertThat(Channels.valid("area_x; DROP TABLE resources")).isFalse();
    }

    @Test
    void onlyTheAreasListenedToArrive() throws Exception {
        String heard = listenedArea();
        String unheard = "area:" + unique();
        tx.executeWithoutResult(s -> publisher.publish(batch(unheard, List.of(change("entity:1", 1, null)), null)));
        tx.executeWithoutResult(s -> publisher.publish(batch(heard, List.of(change("entity:1", 1, null)), null)));
        // one connection's notifications arrive in order: once heard's is here, unheard's would be too
        assertThat(areasUntil(heard)).doesNotContain(unheard);
    }

    @Test
    void theLastUnlistenStopsAnAreasBatches() throws Exception {
        String area = listenedArea();
        subscriber.listen(area).get(10, TimeUnit.SECONDS);
        String channel = Channels.area(area);
        PostgresChangeSubscriber pg = postgres();

        subscriber.unlisten(area);
        Thread.sleep(300);
        assertThat(pg.activeChannels()).contains(channel);

        subscriber.unlisten(area);
        long deadline = System.currentTimeMillis() + 5_000;
        while (pg.activeChannels().contains(channel) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(pg.activeChannels()).doesNotContain(channel);
        String marker = listenedArea();
        tx.executeWithoutResult(s -> publisher.publish(batch(area, List.of(change("entity:1", 1, null)), null)));
        tx.executeWithoutResult(s -> publisher.publish(batch(marker, List.of(change("entity:1", 1, null)), null)));
        assertThat(areasUntil(marker)).doesNotContain(area);
    }

    @Test
    void aBatchThatSetsWhoIsUpSendsATurnNoticeToEverySubscriber() throws Exception {
        String area = "area:" + unique();
        ObjectNode up = Json.MAPPER.createObjectNode();
        up.putObject("activeEntity").put("entity", "entity:1").putObject("controller")
                .put("kind", "service").put("principal", "orbital");
        tx.executeWithoutResult(s -> publisher.publish(batch(area, List.of(
                change("entity:1", 2, null), change(area, ResourceKind.AREA, 5, up, null)), 9L, null)));
        TurnNotice n = noticeFor(area);
        assertThat(n.mustRead()).isFalse();
        assertThat(n.controller().path("principal").asText()).isEqualTo("orbital");
    }

    @Test
    void aReloadSendsANoticeToReadTheArea() throws Exception {
        String area = "area:" + unique();
        tx.executeWithoutResult(s -> publisher.publish(ChangeBatch.reload(area, 3L, null)));
        assertThat(noticeFor(area).mustRead()).isTrue();
    }

    @Test
    void aBatchThatLeavesWhoIsUpAloneSendsNoNotice() throws Exception {
        String area = "area:" + unique();
        String marker = "area:" + unique();
        tx.executeWithoutResult(s -> publisher.publish(batch(area, List.of(change("entity:1", 2, null)), null)));
        tx.executeWithoutResult(s -> publisher.publish(ChangeBatch.reload(marker, 1L, null)));
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            TurnNotice n = notices.poll(200, TimeUnit.MILLISECONDS);
            assertThat(n == null || !area.equals(n.area())).isTrue();
            if (n != null && marker.equals(n.area())) {
                return;
            }
        }
        throw new AssertionError("no notice for " + marker);
    }

    private PostgresChangeSubscriber postgres() throws Exception {
        var f = subscriber.getClass().getDeclaredField("postgresChangeSubscriber");
        f.setAccessible(true);
        return (PostgresChangeSubscriber) f.get(subscriber);
    }

    private List<String> areasUntil(String area) throws InterruptedException {
        List<String> seen = new java.util.ArrayList<>();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ChangeBatch b = received.poll(200, TimeUnit.MILLISECONDS);
            if (b != null) {
                seen.add(b.area());
                if (area.equals(b.area())) {
                    return seen;
                }
            }
        }
        throw new AssertionError("no batch for " + area);
    }

    private TurnNotice noticeFor(String area) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            TurnNotice n = notices.poll(200, TimeUnit.MILLISECONDS);
            if (n != null && area.equals(n.area())) {
                return n;
            }
        }
        throw new AssertionError("no notice for " + area);
    }
}
