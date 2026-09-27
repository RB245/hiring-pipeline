package com.pipeline.domain;

import java.util.Arrays;
import java.util.List;

/**
 * The rule set, consulted as a whole. A transition is legal if any rule allows it, so a
 * new kind of move is a new rule added here rather than an edit to an existing one.
 */
public final class TransitionRules {

    private final List<TransitionRule> rules;

    public TransitionRules(List<TransitionRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static TransitionRules standard() {
        return new TransitionRules(List.of(new AdvanceRule(), new RejectRule()));
    }

    public boolean isLegal(Stage from, Stage to) {
        return rules.stream().anyMatch(rule -> rule.allows(from, to));
    }

    /** In declaration order, so the alternatives offered to the recruiter are stable. */
    public List<Stage> legalTargets(Stage from) {
        return Arrays.stream(Stage.values()).filter(to -> isLegal(from, to)).toList();
    }
}
