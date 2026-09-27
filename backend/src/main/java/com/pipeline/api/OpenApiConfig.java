package com.pipeline.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI pipelineApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Hiring pipeline")
                        .version("v1")
                        .description(
                                """
                                Candidates move one stage at a time and can be rejected at any point before
                                being hired. Every move is recorded as an immutable event; the stage shown on
                                a candidate is a projection of that log and can be rebuilt from it.

                                Two things clients get wrong, both on POST /candidates/{id}/transitions:
                                send an Idempotency-Key so retries do not double-advance anyone, and treat
                                409 as "re-read the candidate and decide again" rather than as a failure to
                                retry blindly.
                                """));
    }
}
