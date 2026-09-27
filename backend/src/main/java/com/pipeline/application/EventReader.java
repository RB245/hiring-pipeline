package com.pipeline.application;

import com.pipeline.domain.StageEvent;
import java.util.List;
import java.util.UUID;

public interface EventReader {

    /**
     * Ascending by seq. The same ordering the projection rebuild depends on, which is
     * why there is one method and not two.
     */
    List<StageEvent> timeline(UUID candidateId);
}
