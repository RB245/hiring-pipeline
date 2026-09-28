package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.search.fields.SearchFixture;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The whole pipeline, one row at a time. A golden file rather than a hundred assertions
 * because the interesting thing about the table is that it can be read end to end: a
 * reviewer can see every form the search box accepts without reading any Java.
 */
class GoldenQueryTest {

    private static final List<String> ACCEPTANCE = List.of(
            "Find Priya Sharma",
            "sharam",
            "Who's in Interview right now?",
            "Who has been stuck in Screening for more than a week?",
            "Who moved to Interview since Monday?",
            "Who reached the Offer stage but didn't get hired?",
            "Everyone except rejected candidates",
            "stage:interview in_stage_for:>3d -status:rejected");

    private final SearchQueryParser parser = SearchFixture.parser();

    record Row(String input, String dsl, String ast) {
        @Override
        public String toString() {
            return input;
        }
    }

    @ParameterizedTest
    @MethodSource("rows")
    void normalisesAndParses(Row row) {
        SearchQuery query = parser.parse(row.input());

        assertThat(query.dsl()).as("normalised DSL").isEqualTo(row.dsl());
        assertThat(AstPrinter.print(query.ast())).as("AST").isEqualTo(row.ast());
    }

    /**
     * Feeding the canonical form back in has to be a no-op. If it were not, the DSL shown
     * by {@code /explain} would not be a query she could edit and re-run, which is most of
     * what it is for.
     */
    @ParameterizedTest
    @MethodSource("rows")
    void theCanonicalFormParsesBackToItself(Row row) {
        assertThat(parser.parse(row.dsl()).dsl()).isEqualTo(row.dsl());
    }

    @Test
    void theFileCarriesEveryAcceptanceRowVerbatim() {
        List<String> inputs = rows().stream().map(Row::input).toList();

        assertThat(inputs).containsAll(ACCEPTANCE);
        assertThat(inputs).hasSizeGreaterThanOrEqualTo(60);
    }

    static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        String input = null;
        String dsl = null;
        try (InputStream stream = GoldenQueryTest.class.getResourceAsStream("/search/golden.txt");
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("in:")) {
                    input = line.substring(3).strip();
                } else if (line.startsWith("dsl:")) {
                    dsl = line.substring(4).strip();
                } else if (line.startsWith("ast:")) {
                    rows.add(new Row(input, dsl, line.substring(4).strip()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }
}
