package com.pipeline.application;

import com.pipeline.domain.Candidate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads only. Split from CandidateWriter so that a read path physically cannot write,
 * and so that moving reads onto a replica later is a change of adapter rather than a
 * change of design.
 */
public interface CandidateReader {

    /**
     * Loads the candidate the domain reasons about. Version and seq are absent by
     * design: both are properties of how the row is stored, not of the candidate.
     */
    Optional<Candidate> load(UUID candidateId);

    List<BoardColumn> board(UUID jobId);

    /** Newest first. Pass a null cursor for the first page. */
    CandidatePage page(UUID jobId, Cursor after, int limit);
}
