package com.pessimisticit.scarcity.resourcemodel.autoconfigure;

import java.sql.DriverManager;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.resourcemodel.ChangePublisher;
import com.pessimisticit.scarcity.resourcemodel.ChangeSubscriber;
import com.pessimisticit.scarcity.resourcemodel.JdbcResourceStore;
import com.pessimisticit.scarcity.resourcemodel.ListenConnectionFactory;
import com.pessimisticit.scarcity.resourcemodel.MicrometerTracing;
import com.pessimisticit.scarcity.resourcemodel.PostgresChangePublisher;
import com.pessimisticit.scarcity.resourcemodel.PostgresChangeSubscriber;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.resourcemodel.TraceparentSource;
import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.tracing.Tracer;

@AutoConfiguration(
        afterName = {"org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration"})
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnSingleCandidate(JdbcTemplate.class)
@EnableConfigurationProperties(ResourceChangesProperties.class)
public class ResourceModelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ResourceStore resourceStore(JdbcTemplate jdbc) {
        return new JdbcResourceStore(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    ChangePublisher changePublisher(JdbcTemplate jdbc, ObjectProvider<TraceparentSource> traceparent) {
        return new PostgresChangePublisher(jdbc, traceparent.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "scarcity.resource-changes", name = "listen", havingValue = "true")
    ChangeSubscriber changeSubscriber(DataSource dataSource, ResourceChangesProperties props) {
        ListenConnectionFactory factory = () -> {
            if (props.getUrl() != null) {
                return DriverManager.getConnection(props.getUrl(), props.getUsername(), props.getPassword());
            }
            if (dataSource instanceof HikariDataSource h) {
                return DriverManager.getConnection(h.getJdbcUrl(), h.getUsername(), h.getPassword());
            }
            return dataSource.getConnection();
        };
        return new LifecycleSubscriber(new PostgresChangeSubscriber(factory));
    }

    /** Starts/stops the subscriber with the application context. */
    static final class LifecycleSubscriber implements ChangeSubscriber, SmartLifecycle {
        private final PostgresChangeSubscriber delegate;
        private volatile boolean running;

        LifecycleSubscriber(PostgresChangeSubscriber delegate) {
            this.delegate = delegate;
        }

        @Override
        public void addListener(Consumer<ChangeBatch> l) {
            delegate.addListener(l);
        }

        @Override
        public boolean isListening() {
            return delegate.isListening();
        }

        @Override
        public boolean awaitListening(java.time.Duration timeout) throws InterruptedException {
            return delegate.awaitListening(timeout);
        }

        @Override
        public void start() {
            delegate.start();
            running = true;
        }

        @Override
        public void stop() {
            delegate.stop();
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    /** Only evaluated when micrometer-tracing is present; keeps its classes out of the core path. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Tracer.class)
    static class TracingConfiguration {
        @Bean
        @ConditionalOnBean(Tracer.class)
        @ConditionalOnMissingBean
        TraceparentSource traceparentSource(Tracer tracer) {
            return MicrometerTracing.source(tracer);
        }
    }
}
