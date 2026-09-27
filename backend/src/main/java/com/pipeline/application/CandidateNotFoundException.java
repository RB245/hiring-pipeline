package com.pipeline.application;

import java.util.UUID;

public class CandidateNotFoundException extends RuntimeException {

    public CandidateNotFoundException(UUID candidateId) {
        super("No candidate " + candidateId);
    }
}
