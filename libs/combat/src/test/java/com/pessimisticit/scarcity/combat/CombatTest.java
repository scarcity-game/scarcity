package com.pessimisticit.scarcity.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The combat catalogue: the standard one plus docs/action-types.md's "Combat" tables, and nothing else. */
class CombatTest {

    private static final Pattern ROW = Pattern.compile("^\\| `([a-z-]+)` \\|");

    private static SortedSet<String> docTable(String heading) throws IOException {
        SortedSet<String> names = new TreeSet<>();
        boolean in = false;
        for (String line : Files.readAllLines(Path.of("../../docs/action-types.md"))) {
            if (line.startsWith("#")) {
                in = line.equals("#### " + heading);
                continue;
            }
            Matcher m = ROW.matcher(line);
            if (in && m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    @Test
    void theCombatCatalogueIsTheStandardOnePlusTheCombatTables() throws IOException {
        Catalogue standard = Catalogue.standard();
        Catalogue combat = Combat.standard();
        assertThat(docTable("Combat action types")).containsExactly(Strike.NAME);
        assertThat(docTable("Combat effect types")).containsExactly(MeleeAttack.NAME);

        SortedSet<String> actions = new TreeSet<>(standard.actionNames());
        actions.addAll(docTable("Combat action types"));
        SortedSet<String> effects = new TreeSet<>(standard.effectNames());
        effects.addAll(docTable("Combat effect types"));
        assertThat(combat.actionNames()).isEqualTo(actions);
        assertThat(combat.effectNames()).isEqualTo(effects);
    }

    @Test
    void addingTheRulesTwiceIsAnError() {
        assertThatThrownBy(() -> Combat.addTo(Combat.standard())).hasMessageContaining("two types named");
    }
}
