package com.pipeline.search;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Every {@link FieldHandler} there is, keyed by the name it answers to.
 *
 * <p>Built from the list of beans rather than injected as a {@code Map<String, FieldHandler>}
 * directly, because Spring keys that map by bean name: routing would then depend on an
 * annotation string that nobody reads, and a field whose bean name drifted from its
 * {@code field()} would fail at query time instead of at startup. Here a duplicate or a
 * mismatch cannot get past the constructor.
 */
@Component
public class FieldRegistry {

    private final Map<String, FieldHandler> handlers = new TreeMap<>();
    private final Set<String> modifierFields = new HashSet<>();
    private final FieldHandler bareTermHandler;

    public FieldRegistry(List<FieldHandler> handlers) {
        FieldHandler claimant = null;
        for (FieldHandler handler : handlers) {
            FieldHandler clash = this.handlers.put(handler.field(), handler);
            if (clash != null) {
                throw new IllegalStateException("Two handlers claim the field \"" + handler.field() + "\": "
                        + clash.getClass().getName() + " and " + handler.getClass().getName());
            }
            modifierFields.addAll(handler.modifiers());
            if (handler.bareTerm("probe").isPresent()) {
                if (claimant != null) {
                    throw new IllegalStateException("Two handlers claim bare terms: "
                            + claimant.getClass().getName() + " and " + handler.getClass().getName());
                }
                claimant = handler;
            }
        }
        this.bareTermHandler = claimant;
    }

    public Optional<FieldHandler> find(String field) {
        return Optional.ofNullable(handlers.get(field));
    }

    /** Alphabetical, so the list in an error message and in autocomplete is stable. */
    public List<String> fields() {
        return List.copyOf(handlers.keySet());
    }

    /** Fields that only mean something attached to another one, such as {@code since}. */
    public boolean isModifier(String field) {
        return modifierFields.contains(field);
    }

    /**
     * The field a bare word falls through to. Absent only in a registry assembled without
     * one, which the parser tests do; the running application always has a claimant, and
     * the builder says so rather than quietly dropping the term.
     */
    public Optional<FieldHandler> bareTermHandler() {
        return Optional.ofNullable(bareTermHandler);
    }
}
