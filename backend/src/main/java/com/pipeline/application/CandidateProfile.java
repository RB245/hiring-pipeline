package com.pipeline.application;

import java.util.UUID;

/**
 * The parts of a candidate the rules never consult. They are kept out of the domain
 * Candidate deliberately: nothing about a phone number affects whether a move is legal.
 */
public record CandidateProfile(UUID jobId, String fullName, String email, String phone, String source) {}
