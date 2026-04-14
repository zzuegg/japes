package zzuegg.ecs.persistence;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * SPI for custom serialization of a component record type. The default
 * binary codec auto-derives from the record's field structure via
 * reflection. Implement this interface to override the encoding for
 * specific types (e.g., compressed formats, schema evolution).
 *
 * @param <T> the component record type
 */
public interface ComponentCodec<T extends Record> {

    /** Write the component to the output stream. */
    void encode(T value, DataOutput out) throws IOException;

    /** Read the component from the input stream. */
    T decode(DataInput in) throws IOException;

    /** The component type this codec handles. */
    Class<T> type();
}
