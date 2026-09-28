package com.pipeline.infrastructure;

import java.util.regex.Pattern;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks email addresses on the way out of the logger rather than at each call site.
 * Call-site discipline is the thing that eventually slips: one {@code log.info} with a
 * candidate in it and the addresses are in the aggregator forever.
 *
 * <p>Enough of the address survives to correlate two lines about the same person
 * without the log itself being a mailing list.
 */
public class MaskEmails implements StructuredLoggingJsonMembersCustomizer<Object> {

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, MaskEmails::mask));
    }

    public static String mask(String value) {
        if (value == null || value.indexOf('@') < 0) {
            return value;
        }
        return EMAIL.matcher(value).replaceAll(match -> {
            String address = match.group();
            int at = address.indexOf('@');
            String local = address.substring(0, at);
            String visible = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
            return visible + "***" + address.substring(at);
        });
    }
}
