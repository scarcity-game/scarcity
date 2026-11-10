package com.pessimisticit.scarcity.auth.observability;

import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * Test helper: registers an {@link InMemorySpanExporter} (Boot adds every SpanExporter bean
 * to its BatchSpanProcessor) and offers flush-and-wait lookups.
 */
@TestConfiguration(proxyBeanMethods = false)
public class InMemorySpans {

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
        return InMemorySpanExporter.create();
    }

    /** 32 lowercase hex chars, never all zero. */
    public static String randomTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 16 lowercase hex chars, never all zero. */
    public static String randomSpanId() {
        return HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
    }

    public static String traceparent(String traceId, String parentSpanId) {
        return "00-" + traceId + "-" + parentSpanId + "-01";
    }

    /** Flushes the batch processor and returns everything exported so far. */
    public static List<SpanData> flush(SdkTracerProvider provider, InMemorySpanExporter exporter) {
        provider.forceFlush().join(10, TimeUnit.SECONDS);
        return exporter.getFinishedSpanItems();
    }

    /**
     * Polls until the exported spans satisfy {@code done}. A server span ends after the
     * response is flushed, so a client can see the response before the span exists.
     */
    public static List<SpanData> awaitSpans(SdkTracerProvider provider, InMemorySpanExporter exporter,
            Predicate<List<SpanData>> done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<SpanData> spans = flush(provider, exporter);
        while (!done.test(spans) && System.nanoTime() < deadline) {
            Thread.sleep(50);
            spans = flush(provider, exporter);
        }
        return spans;
    }
}
