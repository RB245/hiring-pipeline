package com.pipeline.domain;

public sealed interface TransitionRule permits AdvanceRule, RejectRule {
    boolean allows(Stage from, Stage to);
}
