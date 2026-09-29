package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.pipeline.search.fields.SearchFixture;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What every field owes the recruiter, asserted across all of them at once rather than
 * remembered by whoever adds the next one.
 *
 * <p>The interesting one is that a field's own examples have to parse. Those strings are
 * not decoration: they are what autocomplete offers and what an error message tells her to
 * try, so an example that does not parse is the application suggesting a query and then
 * rejecting it. That is a failure nobody would find by using the field, because you only
 * meet it when you already got something wrong.
 */
class FieldContractTest {

    private static final FieldRegistry REGISTRY = SearchFixture.registry();
    private static final SearchQueryParser PARSER = SearchFixture.parser();

    static Stream<FieldHandler> fields() {
        return SearchFixture.handlers().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fields")
    void aFieldNamesItselfInTheFormTheDslAccepts(FieldHandler handler) {
        assertThat(handler.field()).matches("[a-z][a-z_]*");
    }

    /** "since: needs a date" reads as a sentence only because valueKind supplies the noun. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fields")
    void aFieldSaysWhatKindOfValueItTakes(FieldHandler handler) {
        assertThat(handler.valueKind()).isNotBlank();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fields")
    void aFieldOffersAtLeastOneExample(FieldHandler handler) {
        assertThat(handler.examples()).isNotEmpty().allSatisfy(example -> assertThat(example).isNotBlank());
    }

    /**
     * Every example, put back through the parser. A modifier is attached to something it
     * can modify first, because on its own it is correctly an error — which the registry
     * tells us, so this still names no field.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fields")
    void everyExampleItOffersIsAQueryThatParses(FieldHandler handler) {
        for (String example : handler.examples()) {
            String query = REGISTRY.isModifier(handler.field())
                    ? "moved_to:interview " + handler.field() + ":" + example
                    : handler.field() + ":" + example;
            assertThatCode(() -> PARSER.parse(query)).as("%s", query).doesNotThrowAnyException();
        }
    }

    /** The list an error message prints, and the order autocomplete offers them in. */
    @Test
    void theRegistryListsEveryFieldOnceInAStableOrder() {
        List<String> fields = REGISTRY.fields();

        assertThat(fields).doesNotHaveDuplicates().isSorted();
        assertThat(fields).hasSize(SearchFixture.handlers().size());
    }
}
