package com.edwin.eventnotification.application.port.out;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface NotificationClaimRepository {

    List<UUID> claimDueBatch(int limit, String workerId, Duration leaseDuration);
}
