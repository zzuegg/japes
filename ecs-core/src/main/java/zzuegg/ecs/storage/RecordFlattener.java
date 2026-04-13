package zzuegg.ecs.storage;

import java.lang.reflect.RecordComponent;
import java.util.*;

/**
 * Utility for flattening nested record types into a list of primitive fields.
 * <p>
 * Given {@code record Transform(Position pos, Rotation rot)} where
 * {@code record Position(float x, float y, float z)} and
 * {@code record Rotation(float x, float y, float z, float w)}, produces:
 * <pre>
 *   pos_x : float, accessor chain [pos(), x()]
 *   pos_y : float, accessor chain [pos(), y()]
 *   pos_z : float, accessor chain [pos(), z()]
 *   rot_x : float, accessor chain [rot(), x()]
 *   rot_y : float, accessor chain [rot(), y()]
 *   rot_z : float, accessor chain [rot(), z()]
 *   rot_w : float, accessor chain [rot(), w()]
 * </pre>
 */
public final class RecordFlattener {

    private RecordFlattener() {}

    /**
     * A single primitive leaf field in a (possibly nested) record.
     *
     * @param flatName   underscore-joined path name, e.g. "pos_x"
     * @param type       the primitive type (float.class, int.class, etc.)
     * @param accessors  chain of RecordComponent accessors from root to leaf
     */
    public record FlatField(String flatName, Class<?> type, List<RecordComponent> accessors) {}

    /**
     * Check if a record type is SoA-eligible: all leaf fields must be primitives,
     * and nested records must also be SoA-eligible. Detects cycles.
     */
    public static boolean isEligible(Class<? extends Record> type) {
        return isEligible(type, new HashSet<>());
    }

    private static boolean isEligible(Class<? extends Record> type, Set<Class<?>> visiting) {
        if (!visiting.add(type)) return false; // cycle detected
        var comps = type.getRecordComponents();
        if (comps == null || comps.length == 0) {
            visiting.remove(type);
            return false;
        }
        for (var comp : comps) {
            Class<?> fieldType = comp.getType();
            if (fieldType.isPrimitive()) continue;
            if (fieldType.isRecord()) {
                @SuppressWarnings("unchecked")
                var recType = (Class<? extends Record>) fieldType;
                if (!isEligible(recType, visiting)) {
                    visiting.remove(type);
                    return false;
                }
            } else {
                visiting.remove(type);
                return false;
            }
        }
        visiting.remove(type);
        return true;
    }

    /**
     * Flatten a record type into a list of {@link FlatField}s. Each entry
     * represents a primitive leaf field with the full accessor chain from the
     * root record to the leaf value.
     *
     * @throws IllegalArgumentException if the type is not SoA-eligible
     */
    public static List<FlatField> flatten(Class<? extends Record> type) {
        if (!isEligible(type)) {
            throw new IllegalArgumentException("Not SoA-eligible: " + type.getName());
        }
        List<FlatField> result = new ArrayList<>();
        flatten(type, "", List.of(), result);
        return Collections.unmodifiableList(result);
    }

    private static void flatten(Class<? extends Record> type, String prefix,
                                List<RecordComponent> chain, List<FlatField> result) {
        for (var comp : type.getRecordComponents()) {
            Class<?> fieldType = comp.getType();
            String name = prefix.isEmpty() ? comp.getName() : prefix + "_" + comp.getName();
            var newChain = new ArrayList<>(chain);
            newChain.add(comp);

            if (fieldType.isPrimitive()) {
                result.add(new FlatField(name, fieldType, Collections.unmodifiableList(newChain)));
            } else {
                // Must be a record (isEligible already validated)
                @SuppressWarnings("unchecked")
                var recType = (Class<? extends Record>) fieldType;
                flatten(recType, name, newChain, result);
            }
        }
    }

    /**
     * Returns true if the given record type has any nested record fields
     * (i.e., is not purely primitive). Useful for deciding which code path
     * to take in the generator.
     */
    public static boolean hasNestedRecords(Class<? extends Record> type) {
        for (var comp : type.getRecordComponents()) {
            if (comp.getType().isRecord()) return true;
        }
        return false;
    }
}
