package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void aMintedIdHasItsKindsPrefixAndIsValid() {
        for (ResourceKind kind : ResourceKind.values()) {
            String id = Ids.newId(kind);
            assertThat(id).startsWith(kind.prefix() + ":").hasSize(kind.prefix().length() + 37);
            assertThat(Ids.kindOf(id)).isEqualTo(kind);
            assertThat(Ids.isValid(id)).isTrue();
        }
    }

    @Test
    void malformedIdsAreRejected() {
        for (String bad : new String[] {"", "entity", "entity:", "entity:not-a-uuid", "thing:00000000-0000-4000-8000-000000000001",
                "entity:00000000-0000-4000-8000-00000000000A"}) {
            assertThat(Ids.isValid(bad)).as(bad).isFalse();
        }
        assertThatThrownBy(() -> Ids.kindOf("entity:x")).hasMessage("malformed resource id: entity:x");
    }
}
