package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.model.Faction;
import com.pessimisticit.scarcity.world.model.Factions;
import com.pessimisticit.scarcity.world.model.Game;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GameFactionsTest {

    private static final String AREA = "area:00000000-0000-4000-8000-000000000002";
    private static final String GAME = "game:00000000-0000-4000-8000-000000000001";

    private final ResourceStore store = mock(ResourceStore.class);
    private final GameFactions gameFactions = new GameFactions(store);

    @Test
    void anAreaPlaysByItsGamesRelations() {
        Map<String, Faction> arena = Map.of("goblin", new Faction(List.of("beast")));
        when(store.parentOf(AREA)).thenReturn(Optional.of(GAME));
        when(store.get(GAME)).thenReturn(Optional.of(new Game("Arena").withFactions(arena).withId(GAME)));
        assertThat(gameFactions.of(AREA)).isEqualTo(arena);
    }

    @Test
    void aGameWithNoneOrNoGameIsTheStandardRelations() {
        when(store.parentOf(AREA)).thenReturn(Optional.of(GAME));
        when(store.get(GAME)).thenReturn(Optional.of(new Game("Dive game").withId(GAME)));
        assertThat(gameFactions.of(AREA)).isEqualTo(Factions.standard());
        when(store.parentOf(AREA)).thenReturn(Optional.empty());
        assertThat(gameFactions.of(AREA)).isEqualTo(Factions.standard());
    }
}
