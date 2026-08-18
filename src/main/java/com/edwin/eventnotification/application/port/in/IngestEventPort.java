package com.edwin.eventnotification.application.port.in;

import com.edwin.eventnotification.domain.event.Event;

public interface IngestEventPort {

    void ingest(Event event);
}
