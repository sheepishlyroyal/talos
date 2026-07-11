package dev.glade.script.spike;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;

/** Utilities shared by the Phase 0 headless GraalPy embedding spike. */
public final class GraalPySpike {
    private GraalPySpike() {}

    public static Context createContext() {
        Thread.currentThread().setContextClassLoader(GraalPySpike.class.getClassLoader());
        return Context.newBuilder("python")
                .allowHostAccess(HostAccess.EXPLICIT)
                .allowHostClassLookup(className -> false)
                .build();
    }
}
