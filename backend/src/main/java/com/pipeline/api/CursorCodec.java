package com.pipeline.api;

import com.pipeline.application.Cursor;
import com.pipeline.application.SearchCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Keeps the keyset position opaque so clients treat it as a token rather than
 * something to construct. The timestamp is encoded at full precision on purpose:
 * truncating to millis would let a row sharing the truncated instant be skipped or
 * repeated at a page boundary.
 *
 * <p>Two kinds of position, because the list and a search are ordered by different keys.
 * The search form is tagged so that feeding one to the other fails as a malformed cursor
 * rather than as a page from the wrong ordering — which would quietly return the wrong
 * rows instead of an error.
 */
final class CursorCodec {

    private static final String SEARCH_TAG = "s";

    static String encode(Cursor cursor) {
        return pack(cursor.createdAt() + "|" + cursor.id());
    }

    /**
     * The score goes through {@link Double#toString}, which round-trips exactly. It has
     * to: the next page compares against this value for equality to break ties between
     * candidates that scored the same, and a decimal that lost a bit would land between
     * two rows rather than on one.
     */
    static String encode(SearchCursor cursor) {
        return pack(SEARCH_TAG + "|" + Double.toString(cursor.score()) + "|" + cursor.createdAt() + "|"
                + cursor.id());
    }

    static Cursor decode(String encoded) {
        String raw = unpack(encoded);
        if (raw.startsWith(SEARCH_TAG + "|")) {
            throw new MalformedCursorException(encoded);
        }
        try {
            int separator = raw.lastIndexOf('|');
            return new Cursor(Instant.parse(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new MalformedCursorException(encoded);
        }
    }

    static SearchCursor decodeSearch(String encoded) {
        String raw = unpack(encoded);
        try {
            String[] parts = raw.split("\\|", 4);
            if (parts.length != 4 || !SEARCH_TAG.equals(parts[0])) {
                throw new MalformedCursorException(encoded);
            }
            return new SearchCursor(Double.parseDouble(parts[1]), Instant.parse(parts[2]), UUID.fromString(parts[3]));
        } catch (MalformedCursorException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MalformedCursorException(encoded);
        }
    }

    private static String pack(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String unpack(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new MalformedCursorException(encoded);
        }
    }

    private CursorCodec() {}
}
