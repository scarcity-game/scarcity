package com.pessimisticit.scarcity.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One of the repo's services, run from its jar in its own JVM (so services'
 * {@code application.yml} and auto-configuration never meet on one classpath), on free ports.
 */
public final class ServiceProcess implements AutoCloseable {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Duration STARTUP = Duration.ofSeconds(120);

    public final String name;
    public final String url;
    private final int management;
    private final Path log;
    private final Process process;

    /** Starts {@code services/<name>/target/scarcity-<name>.jar} with {@code env}. */
    public ServiceProcess(String name, Map<String, String> env) throws IOException {
        this.name = name;
        File jar = new File(System.getProperty("services.dir", ".."), name + "/target/scarcity-" + name + ".jar");
        assertThat(jar).as("%s's jar: build with -pl <the service> -am, which packages it first", name).isFile();
        int port = freePort();
        this.management = freePort();
        this.url = "http://127.0.0.1:" + port;
        this.log = Files.createTempFile(name + "-it", ".log");
        Map<String, String> all = new HashMap<>(env);
        all.put("SERVER_PORT", Integer.toString(port));
        all.put("MANAGEMENT_SERVER_PORT", Integer.toString(management));
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--sun-misc-unsafe-memory-access=allow", "-jar", jar.getAbsolutePath());
        builder.environment().putAll(all);
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        this.process = builder.start();
    }

    /** Waits until readiness answers 200. */
    public void awaitReady() throws Exception {
        URI readiness = URI.create("http://127.0.0.1:" + management + "/actuator/health/readiness");
        long deadline = System.nanoTime() + STARTUP.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new AssertionError(name + " exited with " + process.exitValue() + ":\n" + tail());
            }
            try {
                if (HTTP.send(HttpRequest.newBuilder(readiness).build(), HttpResponse.BodyHandlers.discarding())
                        .statusCode() == 200) {
                    return;
                }
            } catch (IOException notYet) {
                // still starting
            }
            Thread.sleep(250);
        }
        throw new AssertionError(name + " not ready after " + STARTUP + ":\n" + tail());
    }

    public String tail() throws IOException {
        List<String> lines = Files.readAllLines(log);
        return name + ":\n" + String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    }

    @Override
    public void close() throws InterruptedException {
        process.destroy();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
