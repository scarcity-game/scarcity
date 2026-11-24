package com.pessimisticit.scarcity.world.model.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Item;
import com.pessimisticit.scarcity.world.model.ItemPrototypes;
import com.pessimisticit.scarcity.world.model.ItemTemplate;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Stash;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code settle}, {@code create-item} and {@code stack}: items come to rest in stashes (docs/resources.md). */
class SettleTest {

    private static final Settle SETTLE = new Settle(CreateItem.INSTANCE, Stack.INSTANCE);
    private static final String NEAR = "entity:00000000-0000-4000-8000-0000000000e1";
    private static final String FAR = "entity:00000000-0000-4000-8000-0000000000e2";

    private static Item pebble(double grams) {
        return new ItemTemplate("pebble").of(grams);
    }

    private static Item rubble(double grams) {
        return new ItemTemplate("rubble").of(grams);
    }

    private static FakeContext ctx(Map<String, Entity> stashes) {
        Map<String, Entity> entities = new HashMap<>(TestArea.seed());
        entities.putAll(stashes);
        return new FakeContext(TestArea.ROOM, entities, new ScriptedRandom());
    }

    private static <P extends Record> void run(EffectCall<P> call, FakeContext ctx) {
        call.effect().run(call.params(), ctx);
    }

    @Test
    void itemsAreReckonedByCountOrByMass() {
        assertThat(ItemPrototypes.get("pebble").measure()).isEqualTo(Item.Measure.COUNT);
        assertThat(ItemPrototypes.get("rubble").measure()).isEqualTo(Item.Measure.MASS);
        Item three = pebble(30.0).combine(pebble(31.0)).combine(pebble(29.5));
        assertThat(three.count()).isEqualTo(3.0);
        assertThat(three.mass()).isEqualTo(90.5);
        Item heap = rubble(30.0).combine(rubble(12.5));
        assertThat(heap.count()).isNull();
        assertThat(heap.mass()).isEqualTo(42.5);
        assertThatThrownBy(() -> heap.combine(pebble(30.0))).hasMessageContaining("doesn't combine");
        assertThat(WorldJson.toJson(three))
                .isEqualTo("{\"resourceKind\":\"item\",\"prototype\":\"pebble\",\"name\":\"pebble\",\"measure\":\"count\","
                        + "\"count\":3.0,\"mass\":90.5}");
        assertThat(WorldJson.fromJson(WorldJson.toJson(heap), Item.class)).isEqualTo(heap);
    }

    @Test
    void anItemWithNoStashNearStartsOneOfItsOwn() {
        FakeContext ctx = ctx(Map.of());
        SETTLE.run(new Settle.Params(new Position(5.5, 4.0), pebble(30.0)), ctx);
        assertThat(ctx.created).hasSize(1);
        String stash = ctx.created.getFirst();
        assertThat(ctx.peek(stash)).isInstanceOf(Stash.class);
        assertThat(ctx.peek(stash).position()).isEqualTo(new Position(5.5, 4.0));
        assertThat(ctx.peek(stash).radius()).isEqualTo(0.1);
        assertThat(ctx.result).isEqualTo(new Settle.Result(stash, true));
        assertThat(ctx.triggered).extracting(EffectCall::type).containsExactly("create-item");
        assertThat(ctx.triggered.getFirst().params()).isEqualTo(new CreateItem.Params(stash, pebble(30.0)));
    }

    @Test
    void anItemJoinsTheNearestStashWithinTwiceItsRadius() {
        FakeContext ctx = ctx(Map.of(NEAR, Stash.at(new Position(5.0, 4.0)), FAR, Stash.at(new Position(5.28, 4.0))));
        // 0.1 from NEAR, 0.18 from FAR (within the 0.2 of a 0.1 m stash, but further).
        SETTLE.run(new Settle.Params(new Position(5.1, 4.0), rubble(30.0)), ctx);
        assertThat(ctx.created).isEmpty();
        assertThat(ctx.result).isEqualTo(new Settle.Result(NEAR, false));
        assertThat(ctx.triggered.getFirst().params()).isEqualTo(new CreateItem.Params(NEAR, rubble(30.0)));
    }

    @Test
    void aStashJustOutsideTwiceItsRadiusIsNotJoined() {
        FakeContext ctx = ctx(Map.of(NEAR, Stash.at(new Position(5.0, 4.0))));
        SETTLE.run(new Settle.Params(new Position(5.0, 4.2000001), rubble(30.0)), ctx);
        assertThat(ctx.created).hasSize(1);
        assertThat(((Settle.Result) ctx.result).newStash()).isTrue();
    }

    @Test
    void theJoinDistanceFollowsTheStashsCurrentRadius() {
        Entity big = Stash.at(new Position(5.0, 4.0)).withRadius(0.6);
        FakeContext ctx = ctx(Map.of(NEAR, big));
        SETTLE.run(new Settle.Params(new Position(5.0, 5.1), rubble(30.0)), ctx);
        assertThat(ctx.created).isEmpty();
        assertThat(ctx.result).isEqualTo(new Settle.Result(NEAR, false));
        FakeContext outside = ctx(Map.of(NEAR, big));
        SETTLE.run(new Settle.Params(new Position(5.0, 5.2000001), rubble(30.0)), outside);
        assertThat(outside.created).hasSize(1);
    }

    @Test
    void aLikeItemInTheStashIsStackedOntoAndAnotherKindIsAdded() {
        FakeContext ctx = ctx(Map.of(NEAR, Stash.at(new Position(5.0, 4.0))));
        String pebbles = ctx.createItem(NEAR, pebble(30.0).combine(pebble(30.0)));
        SETTLE.run(new Settle.Params(new Position(5.1, 4.0), pebble(28.0)), ctx);
        assertThat(ctx.triggered).extracting(EffectCall::type).containsExactly("stack");
        run(ctx.triggered.getFirst(), ctx);
        assertThat(ctx.peekItem(pebbles).count()).isEqualTo(3.0);
        assertThat(ctx.peekItem(pebbles).mass()).isEqualTo(88.0);
        assertThat(ctx.result).isEqualTo(new Stack.Result(2.0, 3.0, 60.0, 88.0));

        ctx.triggered.clear();
        SETTLE.run(new Settle.Params(new Position(5.1, 4.0), rubble(5.0)), ctx);
        assertThat(ctx.triggered).extracting(EffectCall::type).containsExactly("create-item");
        run(ctx.triggered.getFirst(), ctx);
        assertThat(ctx.itemsIn(NEAR)).hasSize(2);
    }
}
