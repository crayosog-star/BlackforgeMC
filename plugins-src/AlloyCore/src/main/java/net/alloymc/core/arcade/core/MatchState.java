package net.alloymc.core.arcade.core;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The lifecycle every match goes through. Transitions are checked; anything else is a bug. */
public enum MatchState {
    WAITING_FOR_PLAYERS, COUNTDOWN, MAP_LOADING, TELEPORTING_PLAYERS, STARTING, IN_PROGRESS, ENDING, RESETTING, AVAILABLE;

    private static final Map<MatchState, Set<MatchState>> NEXT = Map.of(
        WAITING_FOR_PLAYERS, EnumSet.of(COUNTDOWN, MAP_LOADING, RESETTING),
        COUNTDOWN, EnumSet.of(WAITING_FOR_PLAYERS, MAP_LOADING, RESETTING),
        MAP_LOADING, EnumSet.of(TELEPORTING_PLAYERS, MAP_LOADING, RESETTING),   // MAP_LOADING -> MAP_LOADING = retry another map
        TELEPORTING_PLAYERS, EnumSet.of(STARTING, ENDING, RESETTING),
        STARTING, EnumSet.of(IN_PROGRESS, ENDING, RESETTING),
        IN_PROGRESS, EnumSet.of(ENDING, RESETTING),
        ENDING, EnumSet.of(RESETTING),
        RESETTING, EnumSet.of(AVAILABLE),
        AVAILABLE, EnumSet.noneOf(MatchState.class));

    public boolean canGo(MatchState to) { return NEXT.get(this).contains(to); }

    /** Players are in the arena and the game is (about to be) running. */
    public boolean live() { return this == TELEPORTING_PLAYERS || this == STARTING || this == IN_PROGRESS; }

    /** Small state machine holder so illegal transitions fail loudly. */
    public static final class Machine {
        private MatchState state;
        public Machine(MatchState start) { this.state = start; }
        public MatchState get() { return state; }
        public void go(MatchState to) {
            if (!state.canGo(to)) throw new IllegalStateException("illegal match transition " + state + " -> " + to);
            state = to;
        }
        public boolean is(MatchState s) { return state == s; }
    }
}
