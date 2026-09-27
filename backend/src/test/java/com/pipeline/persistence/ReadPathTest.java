package com.pipeline.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.application.BoardColumn;
import com.pipeline.application.CandidatePage;
import com.pipeline.application.CandidateProfile;
import com.pipeline.application.CandidateReader;
import com.pipeline.application.CandidateSummary;
import com.pipeline.application.EventReader;
import com.pipeline.application.RegisterCandidate;
import com.pipeline.application.TransitionCandidate;
import com.pipeline.domain.Actor;
import com.pipeline.domain.Stage;
import com.pipeline.domain.StageEvent;
import com.pipeline.support.MutableClock;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReadPathTest extends PersistenceTest {

    private static final Actor ACTOR = new Actor("recruiter-1", "Asha");

    @Autowired RegisterCandidate registerCandidate;
    @Autowired TransitionCandidate transitionCandidate;
    @Autowired CandidateReader reader;
    @Autowired EventReader events;
    @Autowired MutableClock clock;

    private UUID jobId;

    @BeforeEach
    void setUp() throws SQLException {
        jobId = createJob();
    }

    private UUID register(String name) {
        return registerCandidate.register(
                new CandidateProfile(jobId, name, UUID.randomUUID() + "@example.com", null, null), ACTOR);
    }

    @Test
    void theBoardGroupsByStageAndKeepsEmptyColumns() {
        UUID applied = register("Applied Only");
        UUID screening = register("Moved To Screening");
        clock.advance(Duration.ofDays(1));
        transitionCandidate.transition(screening, null, Stage.SCREENING, ACTOR, null, null);

        List<BoardColumn> board = reader.board(jobId);

        assertThat(board).extracting(BoardColumn::stage).containsExactly(Stage.values());
        assertThat(column(board, Stage.APPLIED).candidates()).extracting(CandidateSummary::id).containsExactly(applied);
        assertThat(column(board, Stage.SCREENING).candidates())
                .extracting(CandidateSummary::id)
                .containsExactly(screening);
        assertThat(column(board, Stage.APPLIED).count()).isEqualTo(1);
        assertThat(column(board, Stage.HIRED).count()).isZero();
    }

    @Test
    void timeInStageComesFromTheClockNotFromTheRow() {
        UUID id = register("Priya Sharma");
        clock.advance(Duration.ofDays(9));

        CandidateSummary summary = column(reader.board(jobId), Stage.APPLIED).candidates().get(0);

        assertThat(summary.id()).isEqualTo(id);
        assertThat(summary.timeInCurrentStage(clock)).isEqualTo(Duration.ofDays(9));
    }

    @Test
    void theTimelineIsAscendingBySeq() {
        UUID id = register("Priya Sharma");
        clock.advance(Duration.ofDays(1));
        transitionCandidate.transition(id, null, Stage.SCREENING, ACTOR, null, null);
        clock.advance(Duration.ofDays(1));
        transitionCandidate.transition(id, null, Stage.INTERVIEW, ACTOR, null, null);
        clock.advance(Duration.ofDays(1));
        transitionCandidate.transition(id, null, Stage.REJECTED, ACTOR, "no offer", null);

        assertThat(events.timeline(id))
                .extracting(StageEvent::toStage)
                .containsExactly(Stage.APPLIED, Stage.SCREENING, Stage.INTERVIEW, Stage.REJECTED);
    }

    /**
     * The point of keyset over OFFSET. Two candidates are created after the first page
     * is served, and they sort above everything already returned. With OFFSET the
     * second page would slide back over rows the caller has already seen; anchored to
     * where page one ended, it cannot.
     */
    @Test
    void pagesStayStableWhenRowsAreInsertedBetweenRequests() {
        List<UUID> oldest = List.of(
                register("One"), spaced("Two"), spaced("Three"), spaced("Four"), spaced("Five"));

        CandidatePage first = reader.page(jobId, null, 2);
        assertThat(first.candidates()).extracting(CandidateSummary::id).containsExactly(oldest.get(4), oldest.get(3));
        assertThat(first.next()).isNotNull();

        spaced("Six");
        spaced("Seven");

        CandidatePage second = reader.page(jobId, first.next(), 2);
        assertThat(second.candidates()).extracting(CandidateSummary::id).containsExactly(oldest.get(2), oldest.get(1));

        CandidatePage third = reader.page(jobId, second.next(), 2);
        assertThat(third.candidates()).extracting(CandidateSummary::id).containsExactly(oldest.get(0));
        assertThat(third.next()).isNull();
    }

    /** Same created_at for every row, so only the id tiebreak keeps the order total. */
    @Test
    void pagingIsTotalEvenWhenTimestampsCollide() {
        List<UUID> ids = List.of(register("A"), register("B"), register("C"), register("D"));

        CandidatePage first = reader.page(jobId, null, 2);
        CandidatePage second = reader.page(jobId, first.next(), 2);

        assertThat(first.candidates()).hasSize(2);
        assertThat(second.candidates()).hasSize(2);
        assertThat(first.candidates()).extracting(CandidateSummary::id).doesNotContainAnyElementsOf(
                second.candidates().stream().map(CandidateSummary::id).toList());
        assertThat(second.next()).isNull();
        assertThat(java.util.stream.Stream.concat(
                        first.candidates().stream().map(CandidateSummary::id),
                        second.candidates().stream().map(CandidateSummary::id))
                .toList())
                .containsExactlyInAnyOrderElementsOf(ids);
    }

    private UUID spaced(String name) {
        clock.advance(Duration.ofMinutes(1));
        return register(name);
    }

    private static BoardColumn column(List<BoardColumn> board, Stage stage) {
        return board.stream().filter(c -> c.stage() == stage).findFirst().orElseThrow();
    }
}
