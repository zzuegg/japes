package zzuegg.ecs.query;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FieldFilterParseTest {

    record Health(int hp) {}
    record Named(String name) {}

    @Test
    void unspacedGreaterThanParses() {
        // Previously "hp>0" (no whitespace) failed with
        // 'Invalid filter expression' because the splitter wanted 3 tokens.
        var filter = FieldFilter.parse("hp>0", Health.class);
        assertTrue(filter.test(Map.of(Health.class, new Health(5))));
        assertFalse(filter.test(Map.of(Health.class, new Health(0))));
    }

    @Test
    void unspacedGreaterEqualParses() {
        var filter = FieldFilter.parse("hp>=10", Health.class);
        assertTrue(filter.test(Map.of(Health.class, new Health(10))));
        assertFalse(filter.test(Map.of(Health.class, new Health(9))));
    }

    @Test
    void mixedSpacingParses() {
        assertDoesNotThrow(() -> FieldFilter.parse("hp > 5", Health.class));
        assertDoesNotThrow(() -> FieldFilter.parse("hp >5", Health.class));
        assertDoesNotThrow(() -> FieldFilter.parse("hp> 5", Health.class));
    }

    @Test
    void unsupportedOperatorErrorMentionsOperators() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> FieldFilter.parse("hp ~ 0", Health.class));
        var msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("operator"), "error should mention 'operator'; got: " + msg);
    }

    @Test
    void missingFieldErrorMentionsField() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> FieldFilter.parse("nonexistent > 0", Health.class));
        var msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("nonexistent") || msg.contains("accessor"),
            "error should name the missing field or accessor; got: " + msg);
    }

    @Test
    void equalsStringLiteral() {
        var filter = FieldFilter.parse("name == 'alice'", Named.class);
        assertTrue(filter.test(Map.of(Named.class, new Named("alice"))));
        assertFalse(filter.test(Map.of(Named.class, new Named("bob"))));
    }
}
