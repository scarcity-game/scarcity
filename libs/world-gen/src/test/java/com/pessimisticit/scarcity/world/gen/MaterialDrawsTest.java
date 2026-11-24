package com.pessimisticit.scarcity.world.gen;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Material;
import com.pessimisticit.scarcity.world.model.Materials;
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
}
