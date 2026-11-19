package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Wire-format goldens: JSON Lines files under {@code src/test/resources/golden/}, one
 * value per line, compared byte for byte (key order, number format, absent keys).
 * A changed golden is a contract change (#246). {@code -Dgolden.update=true}
 * rewrites the files from what the code writes now; review the diff.
 */
final class Golden {

    private static final Path DIR = Path.of("src/test/resources/golden");

    private Golden() {
    }

    static List<String> lines(String file) {
        try {
            return Files.readAllLines(DIR.resolve(file), StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.isBlank()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void assertLines(String file, List<String> actual) {
        String text = String.join("\n", actual) + "\n";
        if (Boolean.getBoolean("golden.update")) {
            try {
                Files.writeString(DIR.resolve(file), text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return;
        }
        try {
            assertThat(text).as("golden/" + file).isEqualTo(Files.readString(DIR.resolve(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
