package com.pipeline.api;

import com.pipeline.application.RebuildAllProjections;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin")
class AdminController {

    private final RebuildAllProjections rebuildAll;

    AdminController(RebuildAllProjections rebuildAll) {
        this.rebuildAll = rebuildAll;
    }

    @PostMapping("/rebuild-projections")
    @Operation(
            summary = "Recompute every candidate's denormalised columns from the event log",
            description =
                    "changed is how many actually differed from their log, which is the drift figure. "
                            + "Safe to run at any time: the log is immutable and this only rewrites the cache of it.")
    RebuildAllProjections.Result rebuild() {
        return rebuildAll.rebuildAll();
    }
}
