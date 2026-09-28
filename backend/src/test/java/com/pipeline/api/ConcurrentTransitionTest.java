package com.pipeline.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pipeline.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Two real requests, genuinely in flight at once. Whichever mechanism catches the
 * loser — the stale expectedCurrentStage check if the winner has already committed,
 * optimistic locking on the version if they overlap, the unique index on seq if they
 * overlap more tightly still — the caller sees the same 409, and only one event is
 * ever written. That equivalence is the point: the client has one thing to handle.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentTransitionTest extends IntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper json;

    @Test
    void exactlyOneOfTwoSimultaneousTransitionsWins() throws Exception {
        UUID id = createCandidateOverHttp();
        CyclicBarrier bothReady = new CyclicBarrier(2);

        Callable<ResponseEntity<String>> attempt = () -> {
            bothReady.await();
            return advance(id, null);
        };

        List<ResponseEntity<String>> responses = runTogether(attempt);

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(countEvents(id)).isEqualTo(2);
    }

    /** The same race, but both callers retrying one operation rather than racing to do two. */
    @Test
    void twoSimultaneousRequestsSharingAnIdempotencyKeyStillWriteOneEvent() throws Exception {
        UUID id = createCandidateOverHttp();
        String key = UUID.randomUUID().toString();
        CyclicBarrier bothReady = new CyclicBarrier(2);

        Callable<ResponseEntity<String>> attempt = () -> {
            bothReady.await();
            return advance(id, key);
        };

        List<ResponseEntity<String>> responses = runTogether(attempt);

        assertThat(countEvents(id)).isEqualTo(2);
        assertThat(responses).anyMatch(r -> r.getStatusCode() == HttpStatus.CREATED);
        // The loser is either served the original or told to retry; both are correct,
        // and a retry after a 409 lands on the replay.
        assertThat(responses).allMatch(r ->
                r.getStatusCode() == HttpStatus.CREATED || r.getStatusCode() == HttpStatus.CONFLICT);
        assertThat(advance(id, key).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(countEvents(id)).isEqualTo(2);
    }

    private List<ResponseEntity<String>> runTogether(Callable<ResponseEntity<String>> attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> a = pool.submit(attempt);
            Future<ResponseEntity<String>> b = pool.submit(attempt);
            return List.of(a.get(), b.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID createCandidateOverHttp() throws Exception {
        ResponseEntity<String> created = rest.postForEntity(
                "/api/v1/candidates",
                jsonBody("""
                        {"fullName": "Race Condition", "email": "%s@example.com"}
                        """.formatted(UUID.randomUUID()), null),
                String.class);
        JsonNode body = json.readTree(created.getBody());
        return UUID.fromString(body.get("id").asText());
    }

    private ResponseEntity<String> advance(UUID id, String idempotencyKey) {
        return rest.exchange(
                "/api/v1/candidates/" + id + "/transitions",
                HttpMethod.POST,
                jsonBody("""
                        {"expectedCurrentStage": "APPLIED", "toStage": "SCREENING"}
                        """, idempotencyKey),
                String.class);
    }

    private static HttpEntity<String> jsonBody(String body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", API_KEY);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return new HttpEntity<>(body, headers);
    }
}
