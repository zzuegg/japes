package zzuegg.ecs.query;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.function.Predicate;

/**
 * Predicate-based component filter using MethodHandle for zero-overhead field access.
 * The field accessor is resolved once at creation; evaluation is a direct call.
 */
public sealed interface FieldFilter {

    boolean test(java.util.Map<Class<?>, Record> components);

    // === Factory ===

    static <T extends Record> FieldFilterBuilder<T> of(Class<T> type, String fieldName) {
        return new FieldFilterBuilder<>(type, fieldName);
    }

    static FieldFilter and(FieldFilter... filters) {
        return new AndFilter(List.of(filters));
    }

    static FieldFilter or(FieldFilter... filters) {
        return new OrFilter(List.of(filters));
    }

    /**
     * Parse a simple expression like "hp > 0", "defense >= 10", "name == 'foo'"
     */
    static FieldFilter parse(String expression, Class<? extends Record> type) {
        var parts = expression.trim().split("\\s+", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid filter expression: " + expression);
        }
        var field = parts[0];
        var op = parts[1];
        var value = parts[2];

        var builder = of(type, field);
        return switch (op) {
            case ">" -> builder.greaterThan(parseNumber(value));
            case ">=" -> builder.greaterThanOrEqual(parseNumber(value));
            case "<" -> builder.lessThan(parseNumber(value));
            case "<=" -> builder.lessThanOrEqual(parseNumber(value));
            case "==" -> builder.equalTo(parseComparable(value));
            case "!=" -> builder.notEqualTo(parseComparable(value));
            default -> throw new IllegalArgumentException("Unknown operator: " + op);
        };
    }

    // === Implementations ===

    record SingleFieldFilter(
        Class<? extends Record> componentType,
        MethodHandle accessor,
        Predicate<Object> predicate
    ) implements FieldFilter {
        @Override
        public boolean test(java.util.Map<Class<?>, Record> components) {
            var component = components.get(componentType);
            if (component == null) return false;
            try {
                var value = accessor.invoke(component);
                return predicate.test(value);
            } catch (Throwable e) {
                return false;
            }
        }
    }

    record AndFilter(List<FieldFilter> filters) implements FieldFilter {
        @Override
        public boolean test(java.util.Map<Class<?>, Record> components) {
            for (var filter : filters) {
                if (!filter.test(components)) return false;
            }
            return true;
        }
    }

    record OrFilter(List<FieldFilter> filters) implements FieldFilter {
        @Override
        public boolean test(java.util.Map<Class<?>, Record> components) {
            for (var filter : filters) {
                if (filter.test(components)) return true;
            }
            return false;
        }
    }

    // === Builder ===

    class FieldFilterBuilder<T extends Record> {
        private final Class<T> type;
        private final MethodHandle accessor;

        FieldFilterBuilder(Class<T> type, String fieldName) {
            this.type = type;
            try {
                var method = type.getMethod(fieldName);
                method.setAccessible(true);
                this.accessor = MethodHandles.privateLookupIn(type, MethodHandles.lookup()).unreflect(method);
            } catch (Exception e) {
                throw new IllegalArgumentException("No accessor '" + fieldName + "' on " + type.getName(), e);
            }
        }

        public FieldFilter greaterThan(Object threshold) {
            return new SingleFieldFilter(type, accessor,
                v -> compareValues(v, threshold) > 0);
        }

        public FieldFilter greaterThanOrEqual(Object threshold) {
            return new SingleFieldFilter(type, accessor,
                v -> compareValues(v, threshold) >= 0);
        }

        public FieldFilter lessThan(Object threshold) {
            return new SingleFieldFilter(type, accessor,
                v -> compareValues(v, threshold) < 0);
        }

        public FieldFilter lessThanOrEqual(Object threshold) {
            return new SingleFieldFilter(type, accessor,
                v -> compareValues(v, threshold) <= 0);
        }

        public FieldFilter equalTo(Object expected) {
            return new SingleFieldFilter(type, accessor,
                v -> v != null && v.equals(expected));
        }

        public FieldFilter notEqualTo(Object expected) {
            return new SingleFieldFilter(type, accessor,
                v -> v != null && !v.equals(expected));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static int compareValues(Object actual, Object threshold) {
            if (actual instanceof Number a && threshold instanceof Number t) {
                return Double.compare(a.doubleValue(), t.doubleValue());
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            int result = ((Comparable) actual).compareTo(threshold);
            return result;
        }
    }

    // === Parsing helpers ===

    private static Number parseNumber(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e1) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e2) {
                throw new IllegalArgumentException("Not a number: " + s);
            }
        }
    }

    private static Comparable<?> parseComparable(String s) {
        // Try number first
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        // Strip quotes for strings
        if (s.startsWith("'") && s.endsWith("'")) return s.substring(1, s.length() - 1);
        return s;
    }
}
