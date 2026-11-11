package com.pessimisticit.scarcity.resourcemodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class TracingTest extends AbstractDbTest {

    private InMemorySpanExporter exporter;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        tracer = new OtelTracer(otel.getTracer("test"), new OtelCurrentTraceContext(), event -> {
        });
    }

    @Test
    void publisherFillsTraceparentFromCurrentSpanAndConsumerContinuesIt() throws Exception {
        PostgresChangePublisher pub = new PostgresChangePublisher(jdbc, MicrometerTracing.source(tracer));
        String area = "area:" + unique();
        io.micrometer.tracing.Span producer = tracer.nextSpan().name("producer").start();
        try (RawListener raw = new RawListener();
                Tracer.SpanInScope ignored = tracer.withSpan(producer)) {
            tx.executeWithoutResult(s -> pub.publish(new ChangeBatch(area,
                    List.of(new ChangeBatch.Change("entity:1", ResourceType.ENTITY, 2, null)), null)));
            String expected = "00-" + producer.context().traceId() + "-" + producer.context().spanId() + "-01";
            List<String> payloads = raw.collect(l -> l.stream().anyMatch(p -> p.contains(area)));
            ChangeBatch got = Json.MAPPER.readValue(payloads.get(0), ChangeBatch.class);
            assertThat(got.traceparent()).isEqualTo(expected);

            String consumed = MicrometerTracing.inChildSpan(tracer, got, "consume", () -> {
                io.micrometer.tracing.Span cur = tracer.currentSpan();
                return cur.context().traceId() + "/" + cur.context().spanId();
            });
            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertThat(spans).hasSize(1);
            SpanData child = spans.get(0);
            assertThat(child.getName()).isEqualTo("consume");
            assertThat(child.getTraceId()).isEqualTo(producer.context().traceId());
            assertThat(child.getParentSpanId()).isEqualTo(producer.context().spanId());
            assertThat(consumed).isEqualTo(child.getTraceId() + "/" + child.getSpanId());
        } finally {
            producer.end();
        }
    }

    @Test
    void noSpanMeansNoTraceparentAndMissingParentMeansRootSpan() throws Exception {
        assertThat(MicrometerTracing.source(tracer).current()).isNull();
        MicrometerTracing.inChildSpan(tracer, new ChangeBatch("area:x", List.of(), null), "root", () -> null);
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        assertThat(exporter.getFinishedSpanItems().get(0).getParentSpanId()).isEqualTo("0000000000000000");
    }
}
