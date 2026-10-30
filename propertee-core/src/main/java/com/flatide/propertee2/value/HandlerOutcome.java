package com.flatide.propertee2.value;

/**
 * How a script's {@code on error} handler ended (spec v0.37.0), handed to the host with the failure
 * ({@link TeeError#handler()}). The run fails in every case.
 *
 * @param kind  {@code RETURNED} — the handler reached its end or a {@code return}; {@code FAILED} —
 *              an error stopped it; {@code STOPPED} — the host stopped the run while it ran
 * @param value the value it returned: {@code {}} without a {@code return}, and when it failed or was
 *              stopped
 * @param error the error that stopped it ({@code FAILED} only), else {@code null}
 */
public record HandlerOutcome(Kind kind, Object value, TeeError error) {

    public enum Kind { RETURNED, FAILED, STOPPED }

    public static HandlerOutcome returned(Object value) {
        return new HandlerOutcome(Kind.RETURNED, value, null);
    }

    public static HandlerOutcome failed(TeeError error) {
        return new HandlerOutcome(Kind.FAILED, Values.emptyObject(), error);
    }

    public static HandlerOutcome stopped() {
        return new HandlerOutcome(Kind.STOPPED, Values.emptyObject(), null);
    }
}
