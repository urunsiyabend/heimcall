package com.urunsiyabend.heimcall.common.observability;

import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

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
}
