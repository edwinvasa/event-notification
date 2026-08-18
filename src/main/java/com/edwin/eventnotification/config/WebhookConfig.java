package com.edwin.eventnotification.config;

import javax.net.ssl.SSLSocketFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebhookConfig {

    @Bean
    public SSLSocketFactory sslSocketFactory() {
        return (SSLSocketFactory) SSLSocketFactory.getDefault();
    }
}
