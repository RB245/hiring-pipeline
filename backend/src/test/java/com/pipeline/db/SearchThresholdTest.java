package com.pipeline.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * The threshold the whole name search rests on, proved to be in force rather than assumed.
 *
 * <p>This is the failure mode worth spending a test class on. An unset
 * {@code word_similarity_threshold} does not raise anything: {@code %>} simply stops
 * matching, the search returns an empty list, and the recruiter is told nobody is called
 * that. There is no error to notice and no log line to find.
 *
 * <p>Every connection here is opened directly through the driver rather than borrowed from
 * the pool, because a pool-scoped guarantee would not be the guarantee V9 claims to make.
 * It also connects as pipeline_app, so what is verified is the identity the application
 * actually runs as.
 */
class SearchThresholdTest {

    @Test
    void theThresholdIsInForceOnAFreshlyOpenedConnection() throws SQLException {
        try (Connection fresh = SchemaFixture.asApplication()) {
            assertThat(show(fresh, "pg_trgm.word_similarity_threshold")).isEqualTo("0.5");
        }
    }

    /** A second connection, in case the first one were somehow special. */
    @Test
    void andOnTheNextOne() throws SQLException {
        try (Connection fresh = SchemaFixture.asApplication()) {
            assertThat(show(fresh, "pg_trgm.word_similarity_threshold")).isEqualTo("0.5");
        }
    }

    /**
     * The motivating case from file 02, end to end on a connection nobody has configured.
     */
    @Test
    void sharamMatchesPriyaSharmaOnAConnectionNobodyTouched() throws SQLException {
        try (Connection fresh = SchemaFixture.asApplication()) {
            assertThat(bool(fresh, "SELECT immutable_unaccent('Priya Sharma') %> immutable_unaccent('sharam')"))
                    .isTrue();
        }
    }

    /**
     * That the threshold is load-bearing, not decorative. At pg_trgm's own default of 0.6
     * the same query finds nobody — which is exactly the silent empty result this setting
     * exists to prevent, demonstrated rather than described.
     */
    @Test
    void andWouldNotHaveAtThePgTrgmDefault() throws SQLException {
        try (Connection fresh = SchemaFixture.asApplication();
                Statement statement = fresh.createStatement()) {
            statement.execute("SET pg_trgm.word_similarity_threshold = 0.6");
            assertThat(bool(fresh, "SELECT immutable_unaccent('Priya Sharma') %> immutable_unaccent('sharam')"))
                    .isFalse();
        }
    }

    /** Accent folding travels with it: the index is on the folded name, so the query must be too. */
    @Test
    void accentsAreFoldedBothWays() throws SQLException {
        try (Connection fresh = SchemaFixture.asApplication()) {
            assertThat(bool(fresh, "SELECT immutable_unaccent('Zoë Müller') %> immutable_unaccent('muller')"))
                    .isTrue();
        }
    }

    /**
     * The scoring function's four branches, checked where they are defined. Java asserts
     * on the weights; the shape of a name match is SQL's to answer for.
     */
    @Test
    void theNameScoreRanksExactAbovePrefixAbovePlausibleTypo() throws SQLException {
        try (Connection fresh = SchemaFixture.asApplication()) {
            assertThat(score(fresh, "Priya Sharma", "priya sharma")).isEqualTo(1.0);
            assertThat(score(fresh, "Priya Sharma", "sharm")).isEqualTo(0.85);
            assertThat(score(fresh, "Priya Sharma", "sharam")).isEqualTo(0.80);
            assertThat(score(fresh, "Priya Sharma", "xyzzy")).isZero();
        }
    }

    /**
     * Why the levenshtein floor is there at all. A transposition in a short word scores
     * 0.333 on word similarity, so no threshold in a usable range would admit it, and
     * ranking it on trigram alone would put it below names that merely share letters.
     */
    @Test
    void aTranspositionScoresOnEditDistanceBecauseTrigramsCannotSeeIt() throws SQLException {
        try (Connection fresh = SchemaFixture.asApplication()) {
            assertThat(number(fresh, "SELECT word_similarity('pryia', 'priya sharma')")).isLessThan(0.4);
            assertThat(score(fresh, "Priya Sharma", "pryia")).isEqualTo(0.80);
        }
    }

    private static double score(Connection connection, String name, String term) throws SQLException {
        return number(connection, "SELECT candidate_name_score('" + name + "', '" + term + "')");
    }

    private static String show(Connection connection, String setting) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SHOW " + setting)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static boolean bool(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private static double number(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getDouble(1);
        }
    }
}
