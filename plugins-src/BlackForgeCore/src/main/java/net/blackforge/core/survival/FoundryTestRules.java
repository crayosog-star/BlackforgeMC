package net.blackforge.core.survival;

/** The Foundry's test decisions, kept free of Bukkit types so the build can check them. */
final class FoundryTestRules {
    /** The tier /foundry check turns on. */
    static final int CHECK_TIER = 3;

    private FoundryTestRules() {}

    /** Someone stepping out still has a test running for the leave grace, so the island isn't empty until that runs out. */
    static boolean occupied(boolean anyoneStanding, boolean anyoneTesting) { return anyoneStanding || anyoneTesting; }

    /** A demo agent goes on a bench spot only if nobody's agent is there already, it fits, and this player may build there. */
    static boolean demoSpotFree(boolean agentThere, boolean room, boolean mayBuild) { return !agentThere && room && mayBuild; }

    /**
     * After /foundry check, put the allays back only if its own Tier III went on, someone's still testing and nobody has changed the tier since:
     * an empty island must stay empty, and another tester's choice wins.
     */
    static boolean restoreAfterCheck(boolean tierSet, boolean anyoneTesting, int tierNow) { return tierSet && anyoneTesting && tierNow == CHECK_TIER; }

    /** /foundry check passes the flock only if Tier III went on and its whole configured flock is up. */
    static boolean flockFlying(boolean tierSet, int flying, int flock) { return tierSet && flying == flock; }

    /** The "Allays flying" line's detail, saying so when the allay limit refused the tier change. */
    static String flyingDetail(boolean tierSet, int flying, int flock) {
        return flying + " of " + flock + (tierSet ? "" : " (the tier change was refused by the allay limit)");
    }

    /** /foundry check returns how many demo agents it placed (-1 if one was already running); only a placed one marks a demo. */
    static boolean placedAny(int placed) { return placed > 0; }
}
