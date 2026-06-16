package com.urunsiyabend.heimcall.common.observability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Phase 8 T4: ships fleet-wide tracing defaults so no service yml has to be touched. Registered low in
 * the property-source order, so any service or env (env vars, application.yml) still overrides these.
 *
 * <ul>
 *   <li>{@code sampling.probability=1.0} — trace everything locally (Boot's default is 0.1, which drops
 *       most spans and makes a single curl hard to follow end-to-end). Tune down per env.</li>
 *   <li>{@code otlp.tracing.endpoint} — defaults to the local Jaeger OTLP/HTTP collector; override with
 *       the {@code OTLP_TRACES_ENDPOINT} env var in other environments.</li>
 * </ul>
 */
public class TracingDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "heimcallTracingDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) {
            return;
        }
        Map<String, Object> defaults = Map.of(
                "management.tracing.sampling.probability", "1.0",
                "management.otlp.tracing.endpoint",
                "${OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}");
        // addLast: lowest precedence — real config and env vars win over these defaults.
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
    }
}
