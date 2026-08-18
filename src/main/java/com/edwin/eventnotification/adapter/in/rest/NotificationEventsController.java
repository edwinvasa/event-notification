package com.edwin.eventnotification.adapter.in.rest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.edwin.eventnotification.adapter.in.rest.dto.NotificationDetailResponse;
import com.edwin.eventnotification.adapter.in.rest.dto.NotificationSummaryResponse;
import com.edwin.eventnotification.adapter.in.rest.dto.ReplayResponse;
import com.edwin.eventnotification.application.port.NotificationQueryFilter;
import com.edwin.eventnotification.application.port.in.NotificationQueryPort;
import com.edwin.eventnotification.application.port.in.ReplayNotificationPort;
import com.edwin.eventnotification.application.result.DetailResult;
import com.edwin.eventnotification.application.result.ReplayResult;
import com.edwin.eventnotification.domain.notification.Notification;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

@RestController
@RequestMapping("/notification_events")
public class NotificationEventsController {

    private final NotificationQueryPort notificationQueryPort;
    private final ReplayNotificationPort replayNotificationPort;
    private final ReplayRateLimiter replayRateLimiter;

    public NotificationEventsController(
            NotificationQueryPort notificationQueryPort,
            ReplayNotificationPort replayNotificationPort,
            ReplayRateLimiter replayRateLimiter) {
        this.notificationQueryPort = notificationQueryPort;
        this.replayNotificationPort = replayNotificationPort;
        this.replayRateLimiter = replayRateLimiter;
    }

    @GetMapping
    public List<NotificationSummaryResponse> list(
            @RequestParam(name = "created_from", required = false) Instant createdFrom,
            @RequestParam(name = "created_to", required = false) Instant createdTo,
            @RequestParam(required = false) NotificationStatus status,
            Authentication authentication) {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "created_from must not be after created_to");
        }

        String clientId = (String) authentication.getPrincipal();
        NotificationQueryFilter filter = new NotificationQueryFilter(createdFrom, createdTo, status);
        List<Notification> notifications = notificationQueryPort.list(clientId, filter);
        return notifications.stream().map(NotificationSummaryResponse::from).toList();
    }

    @GetMapping("/{notification_event_id}")
    public NotificationDetailResponse getDetail(
            @PathVariable("notification_event_id") UUID notificationEventId, Authentication authentication) {
        String clientId = (String) authentication.getPrincipal();
        DetailResult result = notificationQueryPort.getDetail(notificationEventId, clientId);
        return NotificationDetailResponse.from(result);
    }

    @PostMapping("/{notification_event_id}/replay")
    public ReplayResponse replay(
            @PathVariable("notification_event_id") UUID notificationEventId, Authentication authentication) {
        String clientId = (String) authentication.getPrincipal();

        RateLimitDecision rateLimitDecision = replayRateLimiter.tryAcquire(clientId);
        if (!rateLimitDecision.allowed()) {
            throw new TooManyReplayRequestsException(rateLimitDecision.retryAfterSeconds());
        }

        ReplayResult result = replayNotificationPort.replay(notificationEventId, clientId);
        return ReplayResponse.from(result);
    }
}
