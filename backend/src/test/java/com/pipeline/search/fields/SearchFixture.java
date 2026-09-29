package com.pipeline.search.fields;

import com.pipeline.search.FieldHandler;
import com.pipeline.search.FieldRegistry;
import com.pipeline.search.SearchQueryParser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * A parser wired the way Spring wires it, without a context to start. Lives in the fields
 * package so the handlers can stay package-private: they are implementation, and the
 * registry is the thing the rest of the application talks to.
 *
 * <p>The clock is a Wednesday, chosen so that every relative date in the tests is
 * unambiguous: "monday" is two days back, "last monday" is nine, and neither collides with
 * "today" the way a Monday would.
 */
public final class SearchFixture {

    public static final Instant NOW = Instant.parse("2025-03-12T09:30:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    public static List<FieldHandler> handlers() {
        return List.of(
                new StageField(),
                new ReachedField(),
                new MovedToField(),
                new SinceField(),
                new BeforeField(),
                new InStageForField(),
                new StatusField(),
                new NameField(),
                new NameLikeField(),
                new AppliedField(),
                new SourceField());
    }

    public static FieldRegistry registry() {
        return new FieldRegistry(handlers());
    }

    public static SearchQueryParser parser() {
        return new SearchQueryParser(registry(), CLOCK);
    }

    private SearchFixture() {}
}
