package com.edwin.eventnotification.application.port.in;

import java.util.UUID;

public interface DeliverNotificationPort {

    void deliver(UUID notificationId);
}
