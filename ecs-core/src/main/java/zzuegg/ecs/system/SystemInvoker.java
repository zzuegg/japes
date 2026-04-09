package zzuegg.ecs.system;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

public final class SystemInvoker {

    private final MethodHandle handle;

    private SystemInvoker(MethodHandle handle) {
        this.handle = handle;
    }

    public static SystemInvoker create(SystemDescriptor descriptor) {
        try {
            var lookup = MethodHandles.privateLookupIn(
                descriptor.method().getDeclaringClass(), MethodHandles.lookup());
            var handle = lookup.unreflect(descriptor.method());

            if (descriptor.instance() != null) {
                handle = handle.bindTo(descriptor.instance());
            }

            return new SystemInvoker(handle);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot create invoker for: " + descriptor.name(), e);
        }
    }

    public void invoke(Object[] args) throws Throwable {
        handle.invokeWithArguments(args);
    }

    public MethodHandle handle() {
        return handle;
    }
}
