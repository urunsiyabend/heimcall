package com.urunsiyabend.heimcall.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.MicrometerProducerListener;

/**
 * Wires correlation-id propagation into any service that has this lib on the classpath:
 * <ul>
 *   <li>servlet apps get the {@link CorrelationIdFilter} (HTTP in/out);</li>
 *   <li>Kafka apps get the {@link CorrelationRecordInterceptor} applied to every
 *       {@link ConcurrentKafkaListenerContainerFactory} via a {@link BeanPostProcessor}, so listeners
 *       inherit the producing request's id with no per-service Kafka config change.</li>
 * </ul>
 * The outbound producer side is registered via {@code spring.kafka.producer.properties.interceptor.classes}
 * (see each service's application.yml), which flows through {@code KafkaProperties} to all producers.
 */
@Configuration(proxyBeanMethods = false)
public class HeimcallObservabilityAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(Filter.class)
    static class ServletCorrelation {
        @Bean
        CorrelationIdFilter correlationIdFilter() {
            return new CorrelationIdFilter();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ConcurrentKafkaListenerContainerFactory.class)
    static class KafkaCorrelation {
        @Bean
        static BeanPostProcessor correlationRecordInterceptorPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof ConcurrentKafkaListenerContainerFactory factory) {
                        factory.setRecordInterceptor(new CorrelationRecordInterceptor());
                    }
                    return bean;
                }
            };
        }
    }

    /**
     * Phase 8 T4: enable Micrometer/OpenTelemetry observation on Kafka producers and listeners so each
     * produce/consume becomes a span on the distributed trace. Spring Boot's
     * {@code spring.kafka.{template,listener}.observation-enabled} flags only reach the template and
     * container factory Boot auto-creates; every service here defines its own {@link KafkaTemplate} and
     * {@link ConcurrentKafkaListenerContainerFactory} beans (DLT + events producers, custom listeners),
     * so we flip observation on them directly via a {@link BeanPostProcessor}. Templates and containers
     * pull the {@code ObservationRegistry} from the application context on start, joining HTTP-server
     * spans into one trace across the {@code HTTP -> produce -> consume} hop. Gated on the tracer so this
     * is inert until the OTel bridge is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({io.micrometer.tracing.Tracer.class, ConcurrentKafkaListenerContainerFactory.class})
    static class KafkaTracing {
        @Bean
        static BeanPostProcessor kafkaObservationPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof KafkaTemplate<?, ?> template) {
                        template.setObservationEnabled(true);
                    } else if (bean instanceof ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
                        factory.getContainerProperties().setObservationEnabled(true);
                    }
                    return bean;
                }
            };
        }
    }

    /**
     * Phase 8 T3: bind native Kafka client metrics (incl. {@code kafka_consumer_fetch_manager_records_lag})
     * on every consumer/producer factory. Spring Boot attaches a {@link MicrometerConsumerListener} only to
     * the {@code ConsumerFactory} it auto-creates; services here define their own factories, so Boot backs
     * off and no client metrics bind.
     *
     * <p>We attach the listeners ourselves after all singletons exist (so the {@link MeterRegistry} is ready)
     * and before the listener containers start, skipping any factory that already carries a Micrometer
     * listener to avoid a double bind. Consumer factories are reached through the
     * {@link ConcurrentKafkaListenerContainerFactory} beans rather than by type: Boot's default container
     * factory cannot autowire a service's {@code ConsumerFactory<String,Object>} bean (its provider is
     * invariant {@code ConsumerFactory<Object,Object>}), so it builds a private, non-bean fallback consumer
     * factory from yaml — invisible to {@code getBeansOfType} but reachable via the container factory.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({DefaultKafkaConsumerFactory.class, MicrometerConsumerListener.class, MeterRegistry.class})
    static class KafkaClientMetrics {
        @Bean
        SmartInitializingSingleton kafkaClientMetricsBinder(ObjectProvider<MeterRegistry> registries,
                                                            ListableBeanFactory beans) {
            return () -> {
                MeterRegistry registry = registries.getIfAvailable();
                if (registry == null) {
                    return;
                }
                beans.getBeansOfType(ConcurrentKafkaListenerContainerFactory.class).values().forEach(cf -> {
                    if (cf.getConsumerFactory() instanceof DefaultKafkaConsumerFactory<?, ?> consumerFactory) {
                        bindConsumer(consumerFactory, registry);
                    }
                });
                beans.getBeansOfType(DefaultKafkaProducerFactory.class).values()
                        .forEach(f -> bindProducer(f, registry));
            };
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static void bindConsumer(DefaultKafkaConsumerFactory<?, ?> factory, MeterRegistry registry) {
            if (factory.getListeners().stream().noneMatch(l -> l instanceof MicrometerConsumerListener)) {
                factory.addListener(new MicrometerConsumerListener(registry));
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static void bindProducer(DefaultKafkaProducerFactory<?, ?> factory, MeterRegistry registry) {
            if (factory.getListeners().stream().noneMatch(l -> l instanceof MicrometerProducerListener)) {
                factory.addListener(new MicrometerProducerListener(registry));
            }
        }
    }
}
