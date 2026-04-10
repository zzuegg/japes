package zzuegg.ecs.query;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FieldFilterErrorHandlingTest {

    record Named(String name) {}

    @Test
    void predicateExceptionPropagatesRatherThanSilentlyExcluding() {
        // A predicate that always throws — previously the test method caught it
        // as Throwable and returned false, silently filtering the entity out.
        // That hides real user bugs (e.g. type mismatch between field and
        // threshold). The filter must let the exception through.
        //
        // Using parse("name > 0", Named.class) gives us exactly that shape:
        // the Named.name() accessor returns String, and compareValues will
        // throw ClassCastException when it tries to cast the String to
        // Comparable-of-Double.
        var filter = FieldFilter.parse("name > 0", Named.class);
        var components = Map.<Class<?>, Record>of(Named.class, new Named("alice"));

        assertThrows(RuntimeException.class, () -> filter.test(components));
    }

    @Test
    void equalToNullMatchesNullFieldValues() {
        // equalTo(null) previously returned false regardless of the actual
        // value because of an explicit `v != null && v.equals(expected)` guard.
        // Use Objects.equals semantics.
        record Optional(String value) {}
        var filter = FieldFilter.of(Optional.class, "value").equalTo(null);
        var withNull = Map.<Class<?>, Record>of(Optional.class, new Optional(null));
        var withVal = Map.<Class<?>, Record>of(Optional.class, new Optional("x"));

        assertTrue(filter.test(withNull), "equalTo(null) should match null field");
        assertFalse(filter.test(withVal));
    }

    @Test
    void notEqualToNullRejectsNullFieldValues() {
        record Optional(String value) {}
        var filter = FieldFilter.of(Optional.class, "value").notEqualTo(null);
        var withNull = Map.<Class<?>, Record>of(Optional.class, new Optional(null));
        var withVal = Map.<Class<?>, Record>of(Optional.class, new Optional("x"));

        assertFalse(filter.test(withNull), "notEqualTo(null) should not match null field");
        assertTrue(filter.test(withVal));
    }

    @Test
    void missingComponentStillReturnsFalseNotThrow() {
        // If the component map doesn't contain the target type at all, the
        // filter should still return false silently — that's a lookup miss,
        // not a user error.
        var filter = FieldFilter.of(Named.class, "name").equalTo("alice");
        var empty = Map.<Class<?>, Record>of();
        assertFalse(filter.test(empty));
    }
}
