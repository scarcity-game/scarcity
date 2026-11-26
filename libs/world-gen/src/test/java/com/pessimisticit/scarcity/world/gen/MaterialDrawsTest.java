package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Material;
import com.pessimisticit.scarcity.world.model.Materials;
import java.util.List;
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
        assertThat(draw.weights()).isEqualTo(LEGACY_WEIGHTS);
        assertThat(Materials.get(LEGACY_NAMES.get(draw.value()))).isEqualTo(material);
    }

    /** The catalogue's weights as they were before bedrock: the draw's recorded params must stay these. */
    private static final List<Double> LEGACY_WEIGHTS =
            List.of(6.0, 5.0, 4.0, 3.0, 3.0, 2.0, 1.0, 3.0, 1.0, 1.0, 1.0, 0.5, 0.25, 0.5);
    private static final List<String> LEGACY_NAMES = List.of("granite", "limestone", "sandstone", "basalt", "slate",
            "marble", "obsidian", "brick", "concrete", "oak", "pine", "iron", "steel", "ice");

    @Test
    void theLevelMaterialDrawIsAsItWasBeforeBedrock() {
        for (long seed = 0; seed < 5000; seed++) {
            Recorder recorder = new Recorder(new SplittableRandom(seed));
            Material material = MaterialDraws.wall(recorder);

            Recorder expected = new Recorder(new SplittableRandom(seed));
            int index = expected.categorical(MaterialDraws.WALL, LEGACY_WEIGHTS);
            assertThat(recorder.draws()).isEqualTo(expected.draws());
            assertThat(material.name()).isEqualTo(LEGACY_NAMES.get(index));
        }
    }

    @Test
    void bedrockIsNeverDrawn() {
        SplittableRandom rng = new SplittableRandom(3);
        for (int i = 0; i < 20_000; i++) {
            assertThat(MaterialDraws.wall(new Recorder(rng)).name()).isNotEqualTo(Materials.BEDROCK);
        }
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
}
