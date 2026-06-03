package com.urunsiyabend.heimcall.test;

import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared, reusable Testcontainers singletons for integration tests.
 * Containers start once per JVM and are wired into Spring via {@code @DynamicPropertySource}
 * in each service's test base class.
 */
public final class IntegrationContainers {

    private IntegrationContainers() {
    }

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    public static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }
}
