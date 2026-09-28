package com.pipeline.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recursive descent over the grammar, lowest precedence outermost: or, and, not, primary.
 * Whitespace is a conjunction, so {@code stage:interview sharma} is two filters rather
 * than one long term.
 *
 * <p>The parse is bounded by a step budget rather than a wall clock. Every production and
 * every token consumed spends one step, so a parse that somehow failed to make progress
 * would stop deterministically and identically on every machine, where a timeout would
 * stop at a different place each run and take the test suite's repeatability with it. The
 * length, predicate and depth guards have already bounded the real work before this runs;
 * the budget exists so "it can never hang" is a property of the code rather than of a
 * careful reading of it.
 */
final class Parser {

    private static final int STEP_BUDGET = 10_000;

    private final List<Token> tokens;
    private int position;
    private int steps;

    Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    Node parse() {
        Node node = or();
        if (!peek().is(TokenType.END)) {
            throw unexpected(peek());
        }
        return node;
    }

    private Node or() {
        step();
        Node first = and();
        if (!isOr(peek())) {
            return first;
        }
        List<Node> children = new ArrayList<>(List.of(first));
        while (isOr(peek())) {
            advance();
            children.add(and());
        }
        return new Node.Or(children, extent(children), sourceExtent(children));
    }

    private Node and() {
        step();
        Node first = not();
        List<Node> children = null;
        while (true) {
            Token token = peek();
            if (isAnd(token)) {
                advance();
            } else if (!startsPrimary(token)) {
                break;
            }
            if (children == null) {
                children = new ArrayList<>(List.of(first));
            }
            children.add(not());
        }
        return children == null ? first : new Node.And(children, extent(children), sourceExtent(children));
    }

    private Node not() {
        step();
        Token token = peek();
        if (token.is(TokenType.MINUS) || isNot(token)) {
            advance();
            Node child = not();
            return new Node.Not(child, token.span().to(child.span()), token.source().to(child.source()));
        }
        return primary();
    }

    private Node primary() {
        step();
        Token token = peek();
        if (token.is(TokenType.LEFT_PAREN)) {
            advance();
            Node inner = or();
            if (!peek().is(TokenType.RIGHT_PAREN)) {
                throw new SearchQueryException(ErrorCode.UNCLOSED_GROUP,
                        "The group opened at character " + token.source().start() + " is never closed."
                                + " Add a closing ).",
                        token.span(), token.source());
            }
            advance();
            return inner;
        }
        if (token.is(TokenType.WORD) && peekAt(1).type().isOperator()) {
            return predicate();
        }
        if (token.is(TokenType.WORD) || token.is(TokenType.QUOTED)) {
            advance();
            return new Node.Term(token.text(), token.span(), token.source());
        }
        throw unexpected(token);
    }

    private Node.Predicate predicate() {
        Token field = advance();
        Token operatorToken = advance();
        Operator operator = operatorOf(operatorToken.type());
        // "in_stage_for:>7d" is the form every query uses, so a comparison sitting just
        // after the colon is the predicate's operator rather than part of its value.
        if (!operatorToken.type().isComparison() && peek().type().isComparison()) {
            operatorToken = advance();
            operator = operatorOf(operatorToken.type());
        }
        Node.Value value = value(field, operatorToken);
        return new Node.Predicate(field.text().toLowerCase(Locale.ROOT), operator, value, null,
                field.span().to(value.span()), field.source().to(value.source()));
    }

    private Node.Value value(Token field, Token operatorToken) {
        Token token = peek();
        boolean present = token.is(TokenType.QUOTED)
                || token.is(TokenType.WORD) && !peekAt(1).type().isOperator();
        if (!present) {
            return new Node.Value("", false,
                    field.span().to(operatorToken.span()), field.source().to(operatorToken.source()));
        }
        advance();
        return new Node.Value(token.text(), token.is(TokenType.QUOTED), token.span(), token.source());
    }

    private static Operator operatorOf(TokenType type) {
        return switch (type) {
            case GREATER_THAN -> Operator.GREATER_THAN;
            case GREATER_OR_EQUAL -> Operator.GREATER_OR_EQUAL;
            case LESS_THAN -> Operator.LESS_THAN;
            case LESS_OR_EQUAL -> Operator.LESS_OR_EQUAL;
            default -> Operator.EQUALS;
        };
    }

    private boolean isOr(Token token) {
        return token.is(TokenType.COMMA) || isKeyword(token, "or");
    }

    private boolean isAnd(Token token) {
        return isKeyword(token, "and");
    }

    private boolean isNot(Token token) {
        return isKeyword(token, "not") || isKeyword(token, "except");
    }

    /**
     * A word is only a connective where no operator follows it, so {@code not:x} is a
     * field rather than a negated nothing. Only ever asked about the current token, which
     * is why the lookahead is a fixed one ahead.
     */
    private boolean isKeyword(Token token, String keyword) {
        return token.isWord(keyword) && !peekAt(1).type().isOperator();
    }

    private boolean startsPrimary(Token token) {
        if (isOr(token) || isAnd(token)) {
            return false;
        }
        return token.is(TokenType.WORD) || token.is(TokenType.QUOTED)
                || token.is(TokenType.LEFT_PAREN) || token.is(TokenType.MINUS);
    }

    private SearchQueryException unexpected(Token token) {
        String message = token.is(TokenType.END)
                ? "The query ends before it is finished."
                : "Did not expect \"" + token.text() + "\" here.";
        return new SearchQueryException(ErrorCode.UNEXPECTED_TOKEN, message, token.span(), token.source());
    }

    private static Span extent(List<Node> children) {
        return children.get(0).span().to(children.get(children.size() - 1).span());
    }

    private static Span sourceExtent(List<Node> children) {
        return children.get(0).source().to(children.get(children.size() - 1).source());
    }

    private Token peek() {
        return peekAt(0);
    }

    private Token peekAt(int ahead) {
        int index = Math.min(position + ahead, tokens.size() - 1);
        return tokens.get(index);
    }

    private Token advance() {
        step();
        Token token = peek();
        if (!token.is(TokenType.END)) {
            position++;
        }
        return token;
    }

    private void step() {
        if (++steps > STEP_BUDGET) {
            throw new SearchQueryException(ErrorCode.QUERY_TOO_COMPLEX,
                    "This query is too complicated to parse. Try splitting it up.",
                    new Span(0, 0), new Span(0, 0));
        }
    }
}
