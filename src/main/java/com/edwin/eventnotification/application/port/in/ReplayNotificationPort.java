package com.edwin.eventnotification.application.port.in;

import java.util.UUID;

import com.edwin.eventnotification.application.result.ReplayResult;

public interface ReplayNotificationPort {

    ReplayResult replay(UUID notificationId, String clientId);
}
