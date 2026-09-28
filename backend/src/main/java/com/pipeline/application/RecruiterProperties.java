package com.pipeline.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The one recruiter, and the key that proves you are her. Both come from the
 * environment; there is no default key, because a default key is a key in the repo.
 *
 * <p>Lives in application rather than api because who the recruiter is decides what
 * goes in the audit trail, which the seeder needs too. The api layer only
 * authenticates against it, and application may not depend on api.
 */
@ConfigurationProperties(prefix = "pipeline.auth")
public record RecruiterProperties(String apiKey, String recruiterId, String recruiterName) {}
