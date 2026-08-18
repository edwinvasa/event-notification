package com.edwin.eventnotification.config;

import java.time.Clock;
import java.util.random.RandomGenerator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RandomGenerator randomGenerator() {
        return RandomGenerator.getDefault();
    }
}
