package com.pipeline.api;

import com.pipeline.domain.Actor;
import java.security.Principal;

/**
 * The authenticated principal, carrying exactly what the audit trail needs. An event
 * that cannot say who caused it is half an audit trail, so this is the only source of
 * actor identity in the application.
 */
public record Recruiter(String id, String name) implements Principal {

    @Override
    public String getName() {
        return id;
    }

    public Actor toActor() {
        return new Actor(id, name);
    }
}
