package net.blackforge.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Survival 2 bookkeeping with no Bukkit types, so the build can test it. */
public final class LabRules {
    private LabRules() {}

    /** Staff votes (+1 / -1, one per person) as {good, bad}. Anything else is ignored. */
    public static int[] tally(List<Integer> votes) {
        int good = 0, bad = 0;
        for (Integer v : votes) {
            if (v == null) continue;
            if (v > 0) good++; else if (v < 0) bad++;
        }
        return new int[]{good, bad};
    }

    /** The newest {@code max} entries of a log, oldest first. */
    public static List<String> keepLast(List<String> log, int max) {
        if (max <= 0) return new ArrayList<>();
        return new ArrayList<>(log.subList(Math.max(0, log.size() - max), log.size()));
    }

    /** Tab completion: the options that start with what was typed, ignoring case. */
    public static List<String> startingWith(List<String> options, String typed) {
        String t = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(t)) out.add(o);
        return out;
    }
}
