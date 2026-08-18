package com.edwin.eventnotification.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * With the application's real default configuration (ingestion.kafka.enabled=false), the listener
 * bean must not exist at all - no listener container is created, so no Kafka broker connection is
 * ever attempted. This is also implicitly proven by every other @SpringBootTest in the whole suite
 * starting successfully without a broker available.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationEventsKafkaListenerDisabledByDefaultTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void listenerBeanDoesNotExistWhenKafkaIngestionIsDisabled() {
        assertThat(applicationContext.getBeanNamesForType(NotificationEventsKafkaListener.class)).isEmpty();
    }
}
