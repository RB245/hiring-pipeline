package com.pipeline.api;

import com.pipeline.domain.Actor;
import org.springframework.stereotype.Component;

/**
 * There is no authentication yet, so every event is attributed to the same placeholder.
 * This exists as a single seam rather than a constant repeated in two controllers: when
 * auth arrives, one method changes and the audit trail starts naming real people.
 */
@Component
public class CurrentActor {

    private static final Actor UNAUTHENTICATED = new Actor("unauthenticated", "Unauthenticated");

    public Actor get() {
        return UNAUTHENTICATED;
    }
}
