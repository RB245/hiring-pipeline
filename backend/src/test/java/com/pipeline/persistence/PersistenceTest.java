package com.pipeline.persistence;

import com.pipeline.db.SchemaFixture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real Postgres, and connected as pipeline_app rather than the owner, so these tests
 * run under exactly the privileges production has. A missing grant fails here rather
 * than in production.
 *
 * <p>Flyway is off because {@link SchemaFixture} has already migrated the shared
 * container; touching it from the property supplier is what triggers that.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PersistenceTest.TestClock.class)
abstract class PersistenceTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SchemaFixture::jdbcUrl);
        registry.add("spring.datasource.username", () -> "pipeline_app");
        registry.add("spring.datasource.password", () -> SchemaFixture.APP_PASSWORD);
        registry.add("spring.flyway.enabled", () -> false);
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2025-03-01T09:00:00Z"));
        }
    }

    /** pipeline_app has SELECT only on job, so fixtures create it as the owner. */
    static UUID createJob() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection owner = SchemaFixture.asOwner();
                PreparedStatement statement =
                        owner.prepareStatement("INSERT INTO job (id, title, created_at) VALUES (?, ?, now())")) {
            statement.setObject(1, id);
            statement.setString(2, "Backend Engineer");
            statement.executeUpdate();
        }
        return id;
    }

    static int countEvents(UUID candidateId) throws SQLException {
        try (Connection owner = SchemaFixture.asOwner();
                PreparedStatement statement =
                        owner.prepareStatement("SELECT count(*) FROM stage_event WHERE candidate_id = ?")) {
            statement.setObject(1, candidateId);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    static boolean candidateExists(UUID candidateId) throws SQLException {
        try (Connection owner = SchemaFixture.asOwner();
                PreparedStatement statement =
                        owner.prepareStatement("SELECT 1 FROM candidate WHERE id = ?")) {
            statement.setObject(1, candidateId);
            try (var rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }
}
