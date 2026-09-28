package com.pipeline.application;

import java.util.List;

/**
 * A candidate that matched, and why.
 *
 * <p>{@code matchedOn} is not decoration. A fuzzy search that cannot say why it returned
 * somebody is a search the recruiter has to take on trust, and the first time it returns a
 * surprise she has no way to tell a good match she did not expect from a bug. Reading
 * "name ~ 'sharam' (0.80)" next to a name she did not type answers that in one line.
 */
public record SearchHit(CandidateSummary candidate, double score, List<String> matchedOn) {

    public SearchHit {
        matchedOn = List.copyOf(matchedOn);
    }
}
