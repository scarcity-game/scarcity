package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Material;
import com.pessimisticit.scarcity.world.model.Materials;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class MaterialDrawsTest {

    @Test
    void aLevelsMaterialIsOneCategoricalDrawByWeight() {
        Recorder recorder = new Recorder(new SplittableRandom(1));

        Material material = MaterialDraws.wall(recorder);

        assertThat(recorder.draws()).hasSize(1);
        Recorder.Named named = recorder.draws().getFirst();
        assertThat(named.name()).isEqualTo("level.material");
        Draw.Categorical draw = (Draw.Categorical) named.draw();
        assertThat(draw.weights()).isEqualTo(Materials.all().stream().map(Material::weight).toList());
        assertThat(Materials.all().get(draw.value())).isEqualTo(material);
    }

    @Test
    void stoneIsTheUsualMaterial() {
        SplittableRandom rng = new SplittableRandom(7);
        int granite = 0;
        int oak = 0;
        for (int i = 0; i < 2000; i++) {
            String name = MaterialDraws.wall(new Recorder(rng)).name();
            granite += name.equals("granite") ? 1 : 0;
            oak += name.equals("oak") ? 1 : 0;
        }
        // Weights 6 and 1 of 31.25: about 384 and 64 in 2000.
        assertThat(granite).isBetween(300, 470);
        assertThat(oak).isBetween(30, 110);
    }

    @Test
    void aGameDrawsOnePatternPerMaterialFromItsCandidates() {
        Recorder recorder = new Recorder(new SplittableRandom(3));

        Map<String, String> patterns = MaterialDraws.patterns(recorder);

        assertThat(patterns.keySet()).containsExactlyElementsOf(Materials.all().stream().map(Material::name).toList());
        patterns.forEach((m, p) -> assertThat(Materials.get(m).patterns()).contains(p));
        List<String> drawn = recorder.draws().stream().map(Recorder.Named::name).toList();
        // Brick, oak and pine have one pattern each, so nothing is drawn for them.
        assertThat(drawn).contains("game.pattern.granite").doesNotContain("game.pattern.brick", "game.pattern.oak");
        assertThat(drawn).hasSize((int) Materials.all().stream().filter(m -> m.patterns().size() > 1).count());
        assertThat(patterns).containsEntry("oak", "timber").containsEntry("pine", "timber");
    }

    @Test
    void differentGamesMayDrawDifferentMaps() {
        Set<Map<String, String>> maps = new HashSet<>();
        for (long seed = 0; seed < 20; seed++) {
            maps.add(MaterialDraws.patterns(new Recorder(new SplittableRandom(seed))));
        }
        assertThat(maps).hasSizeGreaterThan(10);
    }
}
