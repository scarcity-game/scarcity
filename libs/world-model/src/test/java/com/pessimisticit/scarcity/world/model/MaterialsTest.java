package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The materials catalogue, derived masses and a game's material patterns (docs/resources.md, "Materials"). */
class MaterialsTest {

    private static final List<Position> UNIT_SQUARE =
            List.of(new Position(0, 0), new Position(1, 0), new Position(1, 1), new Position(0, 1));

    @Test
    void theCatalogueHasFiveToTwentyMaterialsWithRealDensities() {
        List<Material> all = Materials.all();

        assertThat(all).hasSizeBetween(5, 20);
        assertThat(all.stream().map(Material::name).distinct()).hasSameSizeAs(all);
        // Grams per cubic metre, from pine (0.5 t/m3) to iron (7.87 t/m3).
        assertThat(all).allSatisfy(m -> assertThat(m.density()).isBetween(400_000.0, 8_000_000.0));
        assertThat(Materials.get("granite").density()).isEqualTo(2_700_000.0);
        assertThat(Materials.get("steel").density()).isEqualTo(7_850_000.0);
    }

    @Test
    void anUnknownMaterialIsRejected() {
        assertThatThrownBy(() -> Materials.get("adamant")).hasMessage("material: unknown material 'adamant'");
    }

    @Test
    void aWallWeighsItsDensityTimesItsAreaTimesThreeMetres() {
        Entity wall = Materials.get("oak").wall(UNIT_SQUARE);

        assertThat(wall.prototype()).isEqualTo("wall");
        assertThat(wall.name()).isEqualTo("oak wall");
        assertThat(wall.material()).isEqualTo("oak");
        assertThat(wall.mass()).isEqualTo(750_000.0 * 1.0 * Material.HEIGHT);
        Entity triangle = Materials.get("granite").wall(List.of(new Position(0, 0), new Position(2, 0),
                new Position(0, 1)));
        assertThat(triangle.mass()).isCloseTo(2_700_000.0 * 1.0 * 3.0, within(1e-6));
    }

    @Test
    void thePillarsMassIsDerivedFromGraniteAndItsRadius() {
        Entity pillar = Prototypes.get("pillar");

        assertThat(pillar.material()).isEqualTo("granite");
        assertThat(pillar.mass()).isCloseTo(2_700_000.0 * Math.PI * 0.5 * 0.5 * 3.0, within(1e-6));
        assertThat(pillar.mass()).isCloseTo(6_361_725.0, within(1.0));
    }

    @Test
    void theFallbackMapNamesEachMaterialsFirstPattern() {
        Map<String, String> fallback = Materials.fallbackPatterns();

        assertThat(fallback.keySet()).containsExactlyElementsOf(Materials.all().stream().map(Material::name).toList());
        assertThat(fallback).containsEntry("granite", "ashlar").containsEntry("oak", "timber");
    }

    @Test
    void aMaterialIsCheckedWhenMade() {
        assertThatThrownBy(() -> new Material("Granite", 1.0, 1.0, List.of("ashlar")))
                .hasMessageStartingWith("name:");
        assertThatThrownBy(() -> new Material("granite", 0.0, 1.0, List.of("ashlar")))
                .hasMessageStartingWith("density:");
        assertThatThrownBy(() -> new Material("granite", 1.0, 1.0, List.of())).hasMessageStartingWith("patterns:");
    }

    @Test
    void aGamesMaterialPatternsRoundTripInOrder() {
        Map<String, String> patterns = new LinkedHashMap<>();
        patterns.put("oak", "timber");
        patterns.put("granite", "setts");
        Game game = new Game("Dive game", patterns);

        String json = WorldJson.toJson(game);

        assertThat(json).contains("\"materialPatterns\":{\"oak\":\"timber\",\"granite\":\"setts\"}");
        assertThat(WorldJson.fromJson(json, Resource.class)).isEqualTo(game);
        assertThat(WorldJson.toJson(new Game("Test area"))).doesNotContain("materialPatterns");
        assertThat(WorldJson.fromJson("{\"resourceKind\":\"game\",\"name\":\"g\"}", Game.class).materialPatterns())
                .isEmpty();
    }

    @Test
    void aGameMayOnlyDrawAMaterialWithOneOfItsPatterns() {
        assertThatThrownBy(() -> new Game("g", Map.of("oak", "ashlar")))
                .hasMessageStartingWith("materialPatterns: oak is never drawn with 'ashlar'");
        assertThatThrownBy(() -> new Game("g", Map.of("adamant", "ashlar")))
                .hasMessageStartingWith("materialPatterns: unknown material 'adamant'");
    }
}
