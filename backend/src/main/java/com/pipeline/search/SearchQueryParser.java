package com.pipeline.search;

import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The whole pipeline, in the order the stages were designed: normalise, lex, guard, parse,
 * validate. The only entry point — everything else in this package is a stage of it.
 */
@Component
public class SearchQueryParser {

    private final FieldRegistry registry;
    private final Clock clock;

    public SearchQueryParser(FieldRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    public SearchQuery parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw empty("", "There is nothing to search for.");
        }
        QueryGuards.checkLength(raw);

        NormalizedQuery query = Normalizer.normalise(raw);
        if (query.text().isBlank()) {
            // Everything she typed was filler. Saying so is worth more than an empty
            // result set, which would look like "nobody matches" rather than "I did not
            // find a filter in that".
            throw empty(raw, "\"" + raw.strip() + "\" has nothing in it to filter on.");
        }

        List<Token> tokens = new Lexer(query).tokens();
        QueryGuards.checkShape(tokens);

        Node syntax = new Parser(tokens).parse();
        Node ast = new Validator(registry, query, clock).validate(syntax);
        return new SearchQuery(raw, Dsl.render(syntax), ast);
    }

    private SearchQueryException empty(String raw, String detail) {
        Span span = new Span(0, raw.length());
        return new SearchQueryException(ErrorCode.EMPTY_QUERY,
                detail + " Try a name, or something like stage:interview.", span, span,
                List.of("stage:interview", "status:active"));
    }
}
