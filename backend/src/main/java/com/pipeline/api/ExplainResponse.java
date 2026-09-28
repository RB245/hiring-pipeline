package com.pipeline.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pipeline.search.Node;
import com.pipeline.search.ResolvedValue;
import com.pipeline.search.SearchQuery;
import com.pipeline.search.Span;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Locale;

/**
 * How a sentence was understood: what she typed, what it was read as, and the tree in
 * between with every relative date and duration already turned into the instant it means.
 *
 * <p>This endpoint is three things at once, which is why it earns its place. It is what
 * the search box shows when she asks "what did you do with that?"; it is the seam that
 * makes the whole parser testable over HTTP; and it is how a reviewer can be shown that
 * "since Monday" resolved to a specific Monday rather than to a guess.
 */
@Schema(description = "The parse of a query, with nothing run against the database")
record ExplainResponse(
        @Schema(example = "Who has been stuck in Screening for more than a week?") String query,
        @Schema(description = "Canonical form. Paste it back in and it parses to itself.",
                        example = "stage:screening in_stage_for:>7d")
                String dsl,
        ExplainedNode ast) {

    static ExplainResponse of(SearchQuery query) {
        return new ExplainResponse(query.raw(), query.dsl(), ExplainedNode.of(query.ast()));
    }

    /**
     * One node. Written as a single shape with most of it absent rather than as a
     * polymorphic union, because the consumer is a UI drawing a tree and a discriminator
     * plus optional fields is less to deal with than six schemas.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ExplainedNode(
            @Schema(example = "predicate") String type,
            @Schema(example = "in_stage_for") String field,
            @Schema(description = "Empty for equality", example = ">") String operator,
            @Schema(description = "What she typed on the right of the colon", example = "7d") String value,
            @Schema(
                            description = "What that value turned out to mean, dates and durations resolved",
                            example = "entered the stage on or before 2025-03-05T09:30:00Z")
                    String means,
            @Schema(description = "[start, end) into the normalised DSL") List<Integer> span,
            @Schema(description = "[start, end) into the raw query, which is what the box is showing")
                    List<Integer> source,
            List<ExplainedNode> children) {

        static ExplainedNode of(Node node) {
            return switch (node) {
                case Node.And and -> group("and", and.span(), and.source(), and.children());
                case Node.Or or -> group("or", or.span(), or.source(), or.children());
                case Node.Not not -> group("not", not.span(), not.source(), List.of(not.child()));
                case Node.Predicate predicate -> new ExplainedNode(
                        "predicate",
                        predicate.field(),
                        predicate.operator().render(),
                        predicate.value().text(),
                        means(predicate.resolved()),
                        at(predicate.span()),
                        at(predicate.source()),
                        null);
                case Node.Term term -> new ExplainedNode(
                        "term",
                        null,
                        null,
                        term.text(),
                        "matched fuzzily against the name, and exactly against the email",
                        at(term.span()),
                        at(term.source()),
                        null);
            };
        }

        private static ExplainedNode group(String type, Span span, Span source, List<Node> children) {
            return new ExplainedNode(
                    type, null, null, null, null, at(span), at(source), children.stream().map(ExplainedNode::of).toList());
        }

        /**
         * The whole point of the endpoint. A recruiter cannot check "in_stage_for:&gt;7d"
         * for herself, but she can check "entered the stage on or before 5 March".
         */
        private static String means(ResolvedValue value) {
            return switch (value) {
                case ResolvedValue.StageValue stage -> "the stage " + title(stage.stage().name());
                case ResolvedValue.StatusValue status -> title(status.status().name());
                case ResolvedValue.TextValue text -> "matched fuzzily against the name";
                case ResolvedValue.DateValue date -> date.literal() + " is " + date.instant();
                case ResolvedValue.AgeValue age -> switch (age.operator()) {
                    case GREATER_THAN -> "the timestamp is before " + age.threshold();
                    case GREATER_OR_EQUAL -> "the timestamp is on or before " + age.threshold();
                    case LESS_THAN -> "the timestamp is after " + age.threshold();
                    case LESS_OR_EQUAL -> "the timestamp is on or after " + age.threshold();
                    case EQUALS -> "the timestamp is " + age.threshold();
                };
                case ResolvedValue.MovedToValue moved -> "an event into " + title(moved.stage().name())
                        + moved.since().map(since -> ", at or after " + since.instant()).orElse("")
                        + moved.before().map(before -> ", before " + before.instant()).orElse("");
            };
        }

        private static List<Integer> at(Span span) {
            return List.of(span.start(), span.end());
        }

        private static String title(String name) {
            return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
        }
    }
}
