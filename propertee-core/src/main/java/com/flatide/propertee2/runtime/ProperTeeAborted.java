package com.flatide.propertee2.runtime;

/**
 * Host-facing "the run was cancelled" signal, thrown by
 * {@code ProperTeeInterpreter.execute} when the run was ended via
 * {@code ProperTeeInterpreter.abort()}. Deliberately NOT a {@link ProperTeeError}:
 * a cancelled run is not a script error, and hosts must be able to tell the two
 * apart (e.g. CANCELLED vs FAILED run status).
 */
public class ProperTeeAborted extends RuntimeException {
    public ProperTeeAborted() {
        super("Run aborted");
    }
}
