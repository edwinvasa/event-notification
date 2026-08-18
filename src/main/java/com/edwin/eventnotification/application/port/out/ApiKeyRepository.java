package com.edwin.eventnotification.application.port.out;

import java.util.Optional;

public interface ApiKeyRepository {

    Optional<String> findActiveClientIdByApiKey(String apiKey);
}
