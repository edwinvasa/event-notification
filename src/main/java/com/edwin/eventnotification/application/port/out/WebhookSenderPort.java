package com.edwin.eventnotification.application.port.out;

import com.edwin.eventnotification.domain.delivery.DeliveryOutcome;

public interface WebhookSenderPort {

    DeliveryOutcome send(WebhookDeliveryRequest request);
}
