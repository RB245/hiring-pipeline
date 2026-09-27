package com.pipeline.infrastructure;

import com.pipeline.domain.StageTransitions;
import com.pipeline.domain.TransitionRules;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The domain has no Spring annotations of its own, so its collaborators are assembled
 * here instead.
 */
@Configuration
public class DomainConfig {

    @Bean
    public TransitionRules transitionRules() {
        return TransitionRules.standard();
    }

    @Bean
    public StageTransitions stageTransitions(TransitionRules rules, Clock clock) {
        return new StageTransitions(rules, clock);
    }
}
