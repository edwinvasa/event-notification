package com.edwin.eventnotification.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.edwin.eventnotification.domain.delivery.RetryPolicy;

@Configuration
public class RetryConfig {

    @Bean
    public RetryPolicy retryPolicy(
            @Value("${delivery.retry.base-delay}") Duration baseDelay,
            @Value("${delivery.retry.max-delay}") Duration maxDelay) {
        return new RetryPolicy(baseDelay, maxDelay);
    }
}
