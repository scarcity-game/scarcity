package com.pessimisticit.scarcity.auth.principal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The service principals' API keys (root AGENTS.md, "Auth"), read once at
 * startup from a directory with one file per principal: the file name is the
 * principal, the content its key. That is the layout of the k8s secret
 * {@code service-api-keys} mounted as a volume; names starting with a dot (the
 * mount's {@code ..data} links) are skipped. No directory configured means no
 * keys, and every exchange is refused.
 *
 * <p>Only SHA-256 digests of the keys are kept. {@link #principalFor} compares
 * the digest of the offered key with every configured one in constant time and
 * without stopping at a match. Neither keys nor digests are ever logged.
 */
final class ServiceKeys {

    private static final Pattern PRINCIPAL = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    private record Entry(String principal, byte[] digest) {
    }

    private final List<Entry> entries;

    private ServiceKeys(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    static ServiceKeys none() {
        return new ServiceKeys(List.of());
    }

    /** Reads {@code dir}; a blank path means no keys. Fails startup on a malformed directory. */
    static ServiceKeys fromDirectory(String dir) {
        if (dir == null || dir.isBlank()) {
            return none();
        }
        Path path = Path.of(dir);
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("auth.service-token.keys-dir is not a directory: " + dir);
        }
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(path)) {
            for (Path file : files) {
                read(file).ifPresent(e -> add(entries, e));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read auth.service-token.keys-dir", e);
        }
        return new ServiceKeys(entries);
    }

    private static Optional<Entry> read(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (name.startsWith(".") || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        if (!PRINCIPAL.matcher(name).matches()) {
            throw new IllegalStateException("API key file name is not a principal name: " + name);
        }
        String key = Files.readString(file, StandardCharsets.UTF_8).strip();
        if (key.length() < 16) {
            throw new IllegalStateException("API key for " + name + " is shorter than 16 characters");
        }
        return Optional.of(new Entry(name, digest(key)));
    }

    private static void add(List<Entry> entries, Entry entry) {
        for (Entry other : entries) {
            if (MessageDigest.isEqual(other.digest(), entry.digest())) {
                throw new IllegalStateException("principals " + other.principal() + " and "
                        + entry.principal() + " have the same API key");
            }
        }
        entries.add(entry);
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    List<String> principals() {
        return entries.stream().map(Entry::principal).sorted().toList();
    }

    /** The principal whose key {@code offered} is, if any. */
    Optional<String> principalFor(String offered) {
        byte[] digest = digest(offered);
        String match = null;
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(entry.digest(), digest)) {
                match = entry.principal();
            }
        }
        return Optional.ofNullable(match);
    }

    private static byte[] digest(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
