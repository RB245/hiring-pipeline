package com.pipeline.application;

import com.pipeline.domain.Actor;
import com.pipeline.domain.Candidate;
import com.pipeline.domain.CandidateCreation;
import com.pipeline.domain.Stage;
import com.pipeline.domain.StageTransitions;
import com.pipeline.domain.TransitionDecision;
import com.pipeline.domain.TransitionRules;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backdated histories, not a pile of candidates all created at once. Without them the
 * search features in the next two files return nothing and the whole product looks
 * broken in a demo, so the shape of this data is a feature rather than a chore.
 *
 * <p>Written through the domain and the same writer ports the API uses, not through raw
 * SQL. That costs a little speed and buys two things: every seeded history is one the
 * transition rules would actually permit, and seeding exercises the write path under
 * the restricted application role rather than around it. The job row is the exception
 * and is created by migration V8, because pipeline_app deliberately cannot insert one.
 *
 * <p>Each event gets its own fixed clock. Seeding is replaying history, and history
 * does not happen at startup time.
 */
@Component
@EnableConfigurationProperties(SeedProperties.class)
@ConditionalOnProperty(prefix = "pipeline.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SeedPipeline implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedPipeline.class);

    /** Fixed, so two runs on the same base produce byte-identical data. */
    private static final long RANDOM_SEED = 20250301L;

    private static final List<String> FIRST_NAMES = List.of(
            "Priya", "Rahul", "Ananya", "Vikram", "Meera", "Arjun", "Divya", "Karthik", "Sneha", "Rohit",
            "Aisha", "Tanvi", "Nikhil", "Farah", "Imran", "Lakshmi", "Sanjay", "Neha", "Aditya", "Kavya",
            "Zoe", "Mateo", "Chidi", "Yuki", "Elena", "Omar", "Ingrid", "Tomas", "Amara", "Sofia");

    private static final List<String> LAST_NAMES = List.of(
            "Sharma", "Verma", "Iyer", "Nair", "Reddy", "Kulkarni", "Banerjee", "Chatterjee", "Pillai", "Rao",
            "Khan", "Singh", "Mehta", "Bose", "Gupta", "Joshi", "Menon", "Desai", "Kaur", "Patel",
            "Muller", "Garcia", "Okafor", "Tanaka", "Rossi", "Haddad", "Larsen", "Novak", "Diallo", "Costa");

    private static final List<String> SOURCES = List.of("referral", "linkedin", "careers-page", "agency");

    private final CandidateReader candidates;
    private final CandidateWriter writer;
    private final JobReader jobs;
    private final TransitionRules rules;
    private final RecruiterProperties recruiter;
    private final SeedProperties properties;
    private final Clock clock;

    public SeedPipeline(
            CandidateReader candidates,
            CandidateWriter writer,
            JobReader jobs,
            TransitionRules rules,
            RecruiterProperties recruiter,
            SeedProperties properties,
            Clock clock) {
        this.candidates = candidates;
        this.writer = writer;
        this.jobs = jobs;
        this.rules = rules;
        this.recruiter = recruiter;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        UUID jobId = jobs.singleJobId().orElseThrow(NoJobConfiguredException::new);

        // Idempotent by existence rather than by upsert: restarting the container must
        // not double the board, and one transaction means there is no half-seeded state
        // for this check to misread.
        if (!candidates.allIds(jobId).isEmpty()) {
            log.info("Pipeline already seeded; leaving it alone");
            return;
        }

        Instant base = baseInstant();
        Actor actor = new Actor(recruiter.recruiterId(), recruiter.recruiterName());
        Random random = new Random(RANDOM_SEED);

        int total = properties.candidates();
        for (int i = 0; i < total; i++) {
            seedOne(jobId, actor, i, base, random);
        }

        log.info("Seeded {} candidates with backdated histories from base {}", total, base);
    }

    /**
     * Truncated to the day so the recency guarantees ("moved to Interview in the last
     * three days") stay true whenever this runs, while every offset within a run stays
     * fixed. Pin pipeline.seed.base-instant to make it absolute.
     */
    private Instant baseInstant() {
        String configured = properties.baseInstant();
        return (configured == null || configured.isBlank())
                ? clock.instant().truncatedTo(ChronoUnit.DAYS)
                : Instant.parse(configured);
    }

    private void seedOne(UUID jobId, Actor actor, int index, Instant base, Random random) {
        String fullName = nameFor(index);
        List<Step> history = historyFor(index, base, random);

        UUID id = UUID.nameUUIDFromBytes(("seed-candidate-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CandidateCreation creation = Candidate.register(id, actor, fixedAt(history.get(0).at()));

        writer.insertCandidate(
                new CandidateProfile(
                        jobId,
                        fullName,
                        "%s.%s%d@example.com"
                                .formatted(
                                        fullName.split(" ")[0].toLowerCase(),
                                        fullName.split(" ")[1].toLowerCase(),
                                        index),
                        "+91 90000 %05d".formatted(index),
                        SOURCES.get(index % SOURCES.size())),
                creation.candidate());
        writer.appendEvent(creation.firstEvent());

        Candidate current = creation.candidate();
        for (Step step : history.subList(1, history.size())) {
            StageTransitions transitions = new StageTransitions(rules, fixedAt(step.at()));
            TransitionDecision decision = transitions.transition(current, step.stage(), actor, step.reason(), null);
            writer.appendEvent(decision.event());
            current = new Candidate(
                    id, decision.newStage(), decision.event().occurredAt(), decision.reachedMask());
        }

        writer.updateProjection(id, current.currentStage(), current.currentStageSince(), current.reachedMask());
    }

    private static Clock fixedAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private String nameFor(int index) {
        // Index 0 is Priya Sharma by construction, because the search demo asks for her
        // by a misspelling and she has to exist.
        return FIRST_NAMES.get(index % FIRST_NAMES.size()) + " "
                + LAST_NAMES.get((index / FIRST_NAMES.size() + index) % LAST_NAMES.size());
    }

    /**
     * The first 24 candidates satisfy the guarantees the demo depends on; the rest are
     * spread across the board so it does not look like a test fixture.
     */
    private List<Step> historyFor(int index, Instant base, Random random) {
        List<Step> steps = new ArrayList<>();

        if (index < 10) {
            // Stuck in Screening for well over a week.
            steps.add(new Step(Stage.APPLIED, base.minus(Duration.ofDays(24 + index)), null));
            steps.add(new Step(Stage.SCREENING, base.minus(Duration.ofDays(8 + index)), "CV looks relevant"));
        } else if (index < 15) {
            // Moved to Interview inside the last three days.
            steps.add(new Step(Stage.APPLIED, base.minus(Duration.ofDays(30)), null));
            steps.add(new Step(Stage.SCREENING, base.minus(Duration.ofDays(12)), "Good CV"));
            steps.add(new Step(
                    Stage.INTERVIEW, base.minus(Duration.ofHours(12L + (index - 10) * 12L)), "Strong screen"));
        } else if (index < 21) {
            // Reached Offer and was then rejected.
            steps.add(new Step(Stage.APPLIED, base.minus(Duration.ofDays(70)), null));
            steps.add(new Step(Stage.SCREENING, base.minus(Duration.ofDays(60)), "Worth a look"));
            steps.add(new Step(Stage.INTERVIEW, base.minus(Duration.ofDays(45)), "Solid screen"));
            steps.add(new Step(Stage.OFFER, base.minus(Duration.ofDays(30)), "Panel said yes"));
            steps.add(new Step(Stage.REJECTED, base.minus(Duration.ofDays(20L + index - 15)), "Declined the offer"));
        } else if (index < 24) {
            // Hired.
            steps.add(new Step(Stage.APPLIED, base.minus(Duration.ofDays(90)), null));
            steps.add(new Step(Stage.SCREENING, base.minus(Duration.ofDays(80)), "Strong CV"));
            steps.add(new Step(Stage.INTERVIEW, base.minus(Duration.ofDays(65)), "Great screen"));
            steps.add(new Step(Stage.OFFER, base.minus(Duration.ofDays(50)), "Unanimous"));
            steps.add(new Step(Stage.HIRED, base.minus(Duration.ofDays(40L + index - 21)), "Accepted"));
        } else {
            int appliedDaysAgo = 5 + random.nextInt(85);
            steps.add(new Step(Stage.APPLIED, base.minus(Duration.ofDays(appliedDaysAgo)), null));
            int depth = random.nextInt(6);
            long at = appliedDaysAgo;

            if (depth >= 1 && at > 3) {
                at -= 1 + random.nextInt((int) Math.max(1, at / 3));
                steps.add(new Step(Stage.SCREENING, base.minus(Duration.ofDays(at)), "Screened"));
            }
            if (depth >= 2 && at > 2) {
                at -= 1 + random.nextInt((int) Math.max(1, at / 3));
                steps.add(new Step(Stage.INTERVIEW, base.minus(Duration.ofDays(at)), "Interviewing"));
            }
            if (depth >= 3 && at > 1) {
                at -= 1;
                steps.add(new Step(Stage.OFFER, base.minus(Duration.ofDays(at)), "Offer out"));
            }
            if (depth == 4 && at > 0) {
                steps.add(new Step(Stage.REJECTED, base.minus(Duration.ofDays(Math.max(0, at - 1))), "Not a fit"));
            }
            if (depth == 5 && at > 0) {
                Stage last = steps.get(steps.size() - 1).stage();
                if (last != Stage.OFFER) {
                    steps.add(new Step(Stage.REJECTED, base.minus(Duration.ofDays(Math.max(0, at - 1))), "Withdrew"));
                }
            }
        }
        return steps;
    }

    private record Step(Stage stage, Instant at, String reason) {}
}
