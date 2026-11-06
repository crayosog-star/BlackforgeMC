package net.blackforge.core.hub;

/**
 * The Foundry's leave grace: what one poll does with a player, from where they stand and when their grace runs out.
 * Pure, with no Bukkit types, so it can be unit-tested.
 */
final class FoundryGrace {
    private FoundryGrace() {}

    enum Step {
        /** Nothing to do this poll. */
        NONE,
        /** Walked in: start their test. */
        ENTER,
        /** Came back during the grace: their test keeps running. */
        BACK,
        /** Just stepped out: the grace starts now. */
        STEP_OUT,
        /** Still out, grace not over: show the countdown. */
        COUNTDOWN,
        /** Still out and the grace is over: clear their test. */
        CLEAR
    }

    /**
     * @param inZone standing in the Foundry this poll
     * @param testing has a test running (in the Foundry or in the grace)
     * @param until when their grace ends, or null if they aren't in one
     * @param nowMs the current time
     */
    static Step step(boolean inZone, boolean testing, Long until, long nowMs) {
        if (inZone && !testing) return Step.ENTER;
        if (inZone && until != null) return Step.BACK;
        if (inZone || !testing) return Step.NONE;
        if (until == null) return Step.STEP_OUT;
        return nowMs >= until ? Step.CLEAR : Step.COUNTDOWN;
    }

    /** When a grace that starts now ends. */
    static long deadline(long nowMs, long graceMs) { return nowMs + graceMs; }

    /** Whole seconds shown on the countdown: whole seconds left plus one, so it never reads 0 before the clear. */
    static long secondsLeft(long until, long nowMs) { return (until - nowMs) / 1000 + 1; }
}
