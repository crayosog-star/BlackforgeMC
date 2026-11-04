package net.blackforge.core.survival;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Agent's Academy data. No Bukkit types here so it can be unit-tested without a server. Mastery, perks and
 * specialization are kept per job; traits and lifetime stats belong to the Agent. Unknown job, perk and trait names are
 * kept as they are, so a rollback or a removed perk never rewrites saved data.
 */
final class AgentRecord {
    private static final String PREFIX = "4|";

    /** Empty until an Agent is given an identity. */
    String uid = "";
    final Map<String, JobTrack> jobs = new LinkedHashMap<>();
    final List<String> traits = new ArrayList<>();
    long workDone;
    long minutesWorked;
    /** How many trait rerolls this Agent has had. It stays with the Agent, so reselling can't reset the price. */
    int rerolls;

    /** One job's progress. A null spec means no specialization has been chosen. */
    static final class JobTrack {
        long points;
        final List<String> perks = new ArrayList<>();
        String spec;
        /** Today's raw gains before the daily limit (see AcademyRules.dailyGain), and the UTC date they belong to. */
        long todayPoints;
        String todayDate;
        /** How many times this job's choices were reset, which sets the next price. */
        int respecs;
    }

    /** Text form: 4|uid|workDone|minutesWorked|trait,trait|job:points:spec:perk,perk:todayPoints:todayDate:respecs;job:...|rerolls with each name URL-encoded. */
    String encode() {
        List<String> jobText = new ArrayList<>();
        for (Map.Entry<String, JobTrack> e : jobs.entrySet()) {
            JobTrack t = e.getValue();
            jobText.add(enc(e.getKey()) + ":" + Math.max(0, t.points) + ":" + enc(t.spec) + ":" + encList(t.perks)
                    + ":" + Math.max(0, t.todayPoints) + ":" + enc(t.todayDate) + ":" + Math.max(0, t.respecs));
        }
        return PREFIX + enc(uid) + "|" + Math.max(0, workDone) + "|" + Math.max(0, minutesWorked) + "|"
                + encList(traits) + "|" + String.join(";", jobText) + "|" + Math.max(0, rerolls);
    }

    /** Reads encode()'s text. Anything unreadable gives a new empty record; this never throws. */
    static AgentRecord decode(String text) {
        // older versions stay readable, and are saved as the current format next time
        int version = text == null ? 0 : text.startsWith("1|") ? 1 : text.startsWith("2|") ? 2 : text.startsWith("3|") ? 3 : text.startsWith(PREFIX) ? 4 : 0;
        if (version == 0) return new AgentRecord();
        try {
            String[] parts = text.substring(PREFIX.length()).split("\\|", -1);
            if (parts.length != (version >= 4 ? 6 : 5)) return new AgentRecord();
            AgentRecord r = new AgentRecord();
            r.uid = dec(parts[0]);
            r.workDone = Math.max(0, Long.parseLong(parts[1]));
            r.minutesWorked = Math.max(0, Long.parseLong(parts[2]));
            r.traits.addAll(decList(parts[3]));
            if (version >= 4) r.rerolls = Math.max(0, Integer.parseInt(parts[5]));
            if (!parts[4].isEmpty()) {
                for (String job : parts[4].split(";", -1)) {
                    String[] f = job.split(":", -1);
                    if (f.length != (version == 1 ? 4 : version == 2 ? 6 : 7) || f[0].isEmpty()) return new AgentRecord();
                    JobTrack t = new JobTrack();
                    t.points = Math.max(0, Long.parseLong(f[1]));
                    t.spec = f[2].isEmpty() ? null : dec(f[2]);
                    t.perks.addAll(decList(f[3]));
                    if (version >= 2) {
                        t.todayPoints = Math.max(0, Long.parseLong(f[4]));
                        t.todayDate = f[5].isEmpty() ? null : dec(f[5]);
                    }
                    if (version >= 3) t.respecs = Math.max(0, Integer.parseInt(f[6]));
                    r.jobs.put(dec(f[0]), t);
                }
            }
            return r;
        } catch (RuntimeException e) {
            return new AgentRecord();
        }
    }

    private static String enc(String s) { return s == null ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String dec(String s) { return URLDecoder.decode(s, StandardCharsets.UTF_8); }

    private static String encList(List<String> list) {
        List<String> out = new ArrayList<>();
        for (String s : list) out.add(enc(s));
        return String.join(",", out);
    }

    private static List<String> decList(String text) {
        List<String> out = new ArrayList<>();
        if (!text.isEmpty()) for (String s : text.split(",", -1)) out.add(dec(s));
        return out;
    }
}
