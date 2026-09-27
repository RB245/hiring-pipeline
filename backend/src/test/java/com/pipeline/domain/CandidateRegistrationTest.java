package com.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateRegistrationTest {

    private static final Instant NOW = Instant.parse("2025-03-11T09:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Actor ACTOR = new Actor("recruiter-1", "Asha");

    @Test
    void registeringProducesAnOriginlessFirstEvent() {
        UUID id = UUID.randomUUID();

        CandidateCreation creation = Candidate.register(id, ACTOR, CLOCK);

        assertThat(creation.candidate())
                .isEqualTo(new Candidate(id, Stage.APPLIED, NOW, Stage.APPLIED.bit()));
        assertThat(creation.firstEvent().fromStage()).isNull();
        assertThat(creation.firstEvent().toStage()).isEqualTo(Stage.APPLIED);
        assertThat(creation.firstEvent().eventType()).isEqualTo(EventType.APPLIED);
        assertThat(creation.firstEvent().occurredAt()).isEqualTo(NOW);
        assertThat(creation.firstEvent().actor()).isEqualTo(ACTOR);
    }

    @Test
    void replayRebuildsTheProjectionFromTheLogAlone() {
        UUID id = UUID.randomUUID();
        List<StageEvent> history = List.of(
                event(id, null, Stage.APPLIED, EventType.APPLIED, "2025-03-01T10:00:00Z"),
                event(id, Stage.APPLIED, Stage.SCREENING, EventType.ADVANCED, "2025-03-03T11:00:00Z"),
                event(id, Stage.SCREENING, Stage.INTERVIEW, EventType.ADVANCED, "2025-03-06T12:00:00Z"),
                event(id, Stage.INTERVIEW, Stage.REJECTED, EventType.REJECTED, "2025-03-09T13:00:00Z"));

        Candidate rebuilt = Candidate.replay(id, history);

        assertThat(rebuilt.currentStage()).isEqualTo(Stage.REJECTED);
        assertThat(rebuilt.currentStageSince()).isEqualTo(Instant.parse("2025-03-09T13:00:00Z"));
        assertThat(rebuilt.reachedMask())
                .isEqualTo(Stage.APPLIED.bit() | Stage.SCREENING.bit() | Stage.INTERVIEW.bit()
                        | Stage.REJECTED.bit());
    }

    @Test
    void replayingNothingIsAnError() {
        UUID id = UUID.randomUUID();

        assertThatIllegalArgumentException().isThrownBy(() -> Candidate.replay(id, List.of()));
    }

    private static StageEvent event(UUID id, Stage from, Stage to, EventType type, String at) {
        return new StageEvent(id, from, to, type, Instant.parse(at), ACTOR, null, null);
    }
}
