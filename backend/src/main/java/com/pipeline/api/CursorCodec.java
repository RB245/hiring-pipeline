package com.pipeline.api;

import com.pipeline.application.Cursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Keeps the keyset position opaque so clients treat it as a token rather than
 * something to construct. The timestamp is encoded at full precision on purpose:
 * truncating to millis would let a row sharing the truncated instant be skipped or
 * repeated at a page boundary.
 */
final class CursorCodec {

    static String encode(Cursor cursor) {
        String raw = cursor.createdAt().toString() + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            return new Cursor(Instant.parse(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new MalformedCursorException(encoded);
        }
    }

    private CursorCodec() {}
}
