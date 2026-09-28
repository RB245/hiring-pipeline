package com.pipeline.api;

import com.pipeline.domain.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The single source of actor identity. Every stage_event is attributed through here, so
 * there is no path by which a hardcoded name reaches the audit trail.
 */
@Component
public class CurrentActor {

    public Actor get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Recruiter recruiter)) {
            // Unreachable through the API: /api/** requires ROLE_RECRUITER. Loud rather
            // than falling back to a placeholder, because a placeholder in the audit
            // trail is worse than a failed request.
            throw new IllegalStateException("No authenticated recruiter to attribute this event to");
        }
        return recruiter.toActor();
    }
}
