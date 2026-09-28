package com.pipeline.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "pipeline.search")
public record SearchProperties(
        /**
         * How long the edit-distance retry behind a zero-result suggestion may take before
         * it is abandoned and the suggestion dropped.
         *
         * <p>One second, against a measured 49ms over a seeded pipeline of 200 and 3.0s
         * over 50k. Twenty times the real cost, so an ordinary run never comes near it, and
         * well under the worst case, so a pipeline that outgrew this design degrades into a
         * missing line of text rather than a hanging request.
         */
        @DefaultValue("1s") Duration relaxationBudget) {}
