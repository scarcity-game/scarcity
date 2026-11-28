package com.pessimisticit.scarcity.world.model.rules;

import static com.pessimisticit.scarcity.world.model.rules.TestArea.ORBITER;
import static com.pessimisticit.scarcity.world.model.rules.TestArea.PILLAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Position;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** How rules tag their log entries (docs/log.md, "Messages"). */
class LogTagTest {

    private static final Damage.Params HIT_PILLAR = new Damage.Params(PILLAR, 2.0, 0.5, 0.5, 4.0);

    private static AreaView played(String id) {
        Map<String, Entity> entities = new TreeMap<>(TestArea.seed());
        entities.put(id, entities.get(id).withController(new Controller.User("user-1")));
        return TestArea.view(entities);
    }

    private static AreaView servicePlayed() {
        Map<String, Entity> entities = new TreeMap<>(TestArea.seed());
        entities.put(ORBITER, entities.get(ORBITER).withController(new Controller.Service("orbital")));
        return TestArea.view(entities);
    }

    @Test
    void aLevelIsFrom0To11() {
        assertThat(new LogTag("x", 0).level()).isZero();
        assertThat(new LogTag("x", 11).level()).isEqualTo(11);
        assertThatThrownBy(() -> new LogTag("x", 12)).hasMessageContaining("level");
        assertThatThrownBy(() -> new LogTag("x", -1)).hasMessageContaining("level");
    }

    @Test
    void anEntityIsPlayedByAUserOnlyWhenItsControllerIsAUser() {
        assertThat(played(ORBITER).playedByUser(ORBITER)).isTrue();
        assertThat(servicePlayed().playedByUser(ORBITER)).isFalse();
        assertThat(TestArea.view(TestArea.seed()).playedByUser(PILLAR)).isFalse();
        assertThat(played(ORBITER).playedByUser("entity:gone")).isFalse();
    }

    @Test
    void damageIsLevel5BetweenMobsAnd7WhenAUsersEntityIsHitOrHits() {
        assertThat(Damage.INSTANCE.logTag(HIT_PILLAR, servicePlayed(), ORBITER)).isEqualTo(new LogTag("damage", 5));
        assertThat(Damage.INSTANCE.logTag(HIT_PILLAR, played(PILLAR), ORBITER)).isEqualTo(new LogTag("damage", 7));
        assertThat(Damage.INSTANCE.logTag(HIT_PILLAR, played(ORBITER), ORBITER)).isEqualTo(new LogTag("damage", 7));
    }

    @Test
    void destroyingSomethingWithHpIsADeath() {
        Destroy.Params pillar = new Destroy.Params(PILLAR);
        assertThat(Destroy.INSTANCE.logTag(pillar, servicePlayed(), ORBITER)).isEqualTo(new LogTag("death", 6));
        assertThat(Destroy.INSTANCE.logTag(pillar, played(PILLAR), ORBITER)).isEqualTo(new LogTag("death", 9));
        assertThat(Destroy.INSTANCE.logTag(new Destroy.Params(ORBITER), servicePlayed(), ORBITER))
                .isEqualTo(LogTag.EVENT);
    }

    @Test
    void movementIsLevel3AndItsProgressLevel1() {
        Step step = Step.INSTANCE;
        Step.Params params = new Step.Params(new Position(1.0, 1.0));
        assertThat(step.logTag(params, ORBITER, servicePlayed())).isEqualTo(new LogTag("movement", 3));
        assertThat(step.logTag(params, ORBITER, servicePlayed()).progress()).isEqualTo(new LogTag("movement", 1));
    }

    @Test
    void anUntaggedRuleIsAnEvent() {
        assertThat(Create.INSTANCE.logTag(null, servicePlayed(), ORBITER)).isEqualTo(LogTag.EVENT);
    }
}
