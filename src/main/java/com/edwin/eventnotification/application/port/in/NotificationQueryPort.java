package com.edwin.eventnotification.application.port.in;

import java.util.List;
import java.util.UUID;

import com.edwin.eventnotification.application.port.NotificationQueryFilter;
import com.edwin.eventnotification.application.result.DetailResult;
import com.edwin.eventnotification.domain.notification.Notification;

public interface NotificationQueryPort {

    List<Notification> list(String clientId, NotificationQueryFilter filter);

    DetailResult getDetail(UUID notificationId, String clientId);
}
