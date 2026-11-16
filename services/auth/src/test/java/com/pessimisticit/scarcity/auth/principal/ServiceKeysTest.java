package com.pessimisticit.scarcity.auth.principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loading and matching API keys, without Spring. */
class ServiceKeysTest {

    private static final String ORBITAL = "0123456789abcdef-orbital";
    private static final String OTHER = "0123456789abcdef-other";

    @TempDir Path dir;

    @Test
    void noDirectoryMeansNoKeysAndNothingMatches() {
        for (String blank : new String[] {null, "", "  "}) {
            ServiceKeys keys = ServiceKeys.fromDirectory(blank);
            assertThat(keys.isEmpty()).isTrue();
            assertThat(keys.principalFor("")).isEmpty();
            assertThat(keys.principalFor(ORBITAL)).isEmpty();
        }
    }

    @Test
    void readsTheMountedSecretLayoutSkippingDotEntries() throws Exception {
        // A k8s secret volume: ..<timestamp>/<key> files, ..data -> ..<timestamp>, <key> -> ..data/<key>.
        Path version = Files.createDirectory(dir.resolve("..2026_11_14_10_00_00.000000001"));
        Files.writeString(version.resolve("orbital"), ORBITAL + "\n");
        Files.writeString(version.resolve("other"), OTHER);
        Files.createSymbolicLink(dir.resolve("..data"), version.getFileName());
        Files.createSymbolicLink(dir.resolve("orbital"), Path.of("..data/orbital"));
        Files.createSymbolicLink(dir.resolve("other"), Path.of("..data/other"));

        ServiceKeys keys = ServiceKeys.fromDirectory(dir.toString());
        assertThat(keys.principals()).containsExactly("orbital", "other");
        assertThat(keys.principalFor(ORBITAL)).contains("orbital");
        assertThat(keys.principalFor(OTHER)).contains("other");
        assertThat(keys.principalFor(ORBITAL + "\n")).isEmpty();
        assertThat(keys.principalFor("")).isEmpty();
        assertThat(keys.principalFor(ORBITAL.substring(1))).isEmpty();
    }

    @Test
    void aMalformedDirectoryFailsStartup() throws Exception {
        assertThatThrownBy(() -> ServiceKeys.fromDirectory(dir.resolve("missing").toString()))
                .isInstanceOf(IllegalStateException.class);

        Path shortKey = Files.createDirectory(dir.resolve("short"));
        Files.writeString(shortKey.resolve("orbital"), "too-short\n");
        assertThatThrownBy(() -> ServiceKeys.fromDirectory(shortKey.toString()))
                .hasMessageContaining("orbital").hasMessageNotContaining("too-short");

        Path badName = Files.createDirectory(dir.resolve("name"));
        Files.writeString(badName.resolve("Orbital Key"), ORBITAL);
        assertThatThrownBy(() -> ServiceKeys.fromDirectory(badName.toString()))
                .hasMessageContaining("principal name").hasMessageNotContaining(ORBITAL);

        Path same = Files.createDirectory(dir.resolve("same"));
        Files.writeString(same.resolve("orbital"), ORBITAL);
        Files.writeString(same.resolve("other"), ORBITAL);
        assertThatThrownBy(() -> ServiceKeys.fromDirectory(same.toString()))
                .hasMessageContaining("same API key").hasMessageNotContaining(ORBITAL);
    }
}
