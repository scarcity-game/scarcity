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

        assertThat(fallback.keySet()).containsExactlyElementsOf(
                Materials.all().stream().filter(Material::drawable).map(Material::name).toList());
        assertThat(fallback).containsEntry("granite", "ashlar").containsEntry("oak", "timber");
    }

    @Test
    void bedrockIsAMarkerNoLevelOrGameDraws() {
        Material bedrock = Materials.get(Materials.BEDROCK);

        assertThat(bedrock.name()).isEqualTo("bedrock");
        assertThat(bedrock.density()).isEqualTo(3_000_000.0);
        assertThat(bedrock.weight()).isZero();
        assertThat(bedrock.drawable()).isFalse();
        assertThat(bedrock.patterns()).containsExactly("bedrock");
        assertThat(Materials.all().getLast()).isEqualTo(bedrock);
        assertThat(Materials.fallbackPatterns()).doesNotContainKey("bedrock");
        for (long seed = 0; seed < 200; seed++) {
            assertThat(MaterialPatterns.draw(seed).patterns()).doesNotContainKey("bedrock");
        }
        assertThat(Materials.get("granite").drawable()).isTrue();
        assertThat(bedrock.wall(UNIT_SQUARE).name()).isEqualTo("bedrock wall");
    }

    @Test
    void aWeightCannotBeNegative() {
        assertThatThrownBy(() -> new Material("granite", 1.0, -1.0, List.of("ashlar")))
                .hasMessageStartingWith("weight:");
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
    void aHandBuiltGameMayOnlyDrawAMaterialWithOneOfItsPatterns() {
        assertThatThrownBy(() -> new Game("g", Map.of("oak", "ashlar")))
                .hasMessageStartingWith("materialPatterns: oak is never drawn with 'ashlar'");
        assertThatThrownBy(() -> new Game("g", Map.of("adamant", "ashlar")))
                .hasMessageStartingWith("materialPatterns: unknown material 'adamant'");
    }

    @Test
    void aSeededGamesMapIsDrawnFromItsSeedAlone() {
        GameSeed seed = new GameSeed(42L, "abc123");
        Game a = new Game("Dive game", seed);
        Game b = new Game("Dive game", new GameSeed(42L, "def456"));

        assertThat(a.materialPatterns()).isEqualTo(b.materialPatterns())
                .isEqualTo(MaterialPatterns.draw(42L).patterns());
        assertThat(a.materialPatterns().keySet())
                .containsExactlyElementsOf(Materials.all().stream().filter(Material::drawable).map(Material::name).toList());
        a.materialPatterns().forEach((m, p) -> assertThat(Materials.get(m).patterns()).contains(p));
        assertThat(a.materialPatterns()).containsEntry("oak", "timber").containsEntry("pine", "timber");
        assertThat(a.remade()).isFalse();

        String json = WorldJson.toJson(a);
        assertThat(json).contains("\"seed\":{\"value\":42,\"build\":\"abc123\"}");
        assertThat(WorldJson.fromJson(json, Game.class)).isEqualTo(a);
    }

    @Test
    void aMaterialWithOnePatternTakesItWithoutADraw() {
        Map<String, Draw> draws = MaterialPatterns.draw(3L).draws();

        assertThat(draws).containsKey("game.pattern.granite").doesNotContainKeys("game.pattern.brick",
                "game.pattern.oak", "game.pattern.pine");
        assertThat(draws).hasSize((int) Materials.all().stream().filter(m -> m.patterns().size() > 1).count());
        assertThat(draws.values()).allSatisfy(d -> assertThat(d).isInstanceOf(Draw.Uniform.class));
    }

    @Test
    void differentSeedsMayDrawDifferentMaps() {
        java.util.Set<Map<String, String>> maps = new java.util.HashSet<>();
        for (long seed = 0; seed < 20; seed++) {
            maps.add(MaterialPatterns.draw(seed).patterns());
        }
        assertThat(maps).hasSizeGreaterThan(10);
    }

    @Test
    void aStoredMapThatNoLongerFitsTheCatalogueIsMadeAgainFromTheSeed() {
        String stored = "{\"resourceKind\":\"game\",\"name\":\"Dive game\",\"seed\":{\"value\":7,\"build\":\"old\"},"
                + "\"materialPatterns\":{\"adamant\":\"ashlar\",\"oak\":\"timber\"}}";

        Game game = WorldJson.fromJson(stored, Game.class);

        assertThat(game.remade()).isTrue();
        assertThat(game.materialPatterns()).isEqualTo(MaterialPatterns.draw(7L).patterns());
        assertThat(WorldJson.toJson(game)).doesNotContain("adamant").doesNotContain("remade");
    }

    @Test
    void aStoredPatternNoLongerAllowedIsMadeAgainToo() {
        String seeded = "{\"resourceKind\":\"game\",\"name\":\"g\",\"seed\":{\"value\":9,\"build\":\"old\"},"
                + "\"materialPatterns\":{\"oak\":\"ashlar\"}}";
        String unseeded = "{\"resourceKind\":\"game\",\"name\":\"g\",\"materialPatterns\":{\"oak\":\"ashlar\"}}";

        assertThat(WorldJson.fromJson(seeded, Game.class).materialPatterns())
                .isEqualTo(MaterialPatterns.draw(9L).patterns());
        Game handBuilt = WorldJson.fromJson(unseeded, Game.class);
        assertThat(handBuilt.remade()).isTrue();
        assertThat(handBuilt.materialPatterns()).as("no seed: each material's first")
                .isEqualTo(Materials.fallbackPatterns());
    }

    @Test
    void aSeedKnowsWhetherAnotherBuildMadeIt() {
        GameSeed seed = GameSeed.draw(new java.util.SplittableRandom(1), "abc123");

        assertThat(seed.value()).isBetween(0L, GameSeed.LIMIT - 1);
        assertThat(seed.sameBuild("abc123")).isTrue();
        assertThat(seed.sameBuild("def456")).isFalse();
        assertThat(new GameSeed(1L, GameSeed.UNKNOWN_BUILD).sameBuild(GameSeed.UNKNOWN_BUILD))
                .as("a local build can't tell").isFalse();
        assertThatThrownBy(() -> new GameSeed(-1L, "abc")).hasMessageStartingWith("value:");
        assertThatThrownBy(() -> new GameSeed(1L, "a b")).hasMessageStartingWith("build:");
    }
}
