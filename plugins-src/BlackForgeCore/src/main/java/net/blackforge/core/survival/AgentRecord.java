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
    private static final String PREFIX = "2|";
    /** Records saved before the daily fields existed. Still readable, and saved as the current format next time. */
    private static final String OLD_PREFIX = "1|";

    /** Empty until an Agent is given an identity. */
    String uid = "";
    final Map<String, JobTrack> jobs = new LinkedHashMap<>();
    final List<String> traits = new ArrayList<>();
    long workDone;
    long minutesWorked;

    /** One job's progress. A null spec means no specialization has been chosen. */
    static final class JobTrack {
        long points;
        final List<String> perks = new ArrayList<>();
        String spec;
        /** Today's raw gains before the daily limit (see AcademyRules.dailyGain), and the UTC date they belong to. */
        long todayPoints;
        String todayDate;
    }

    /** Text form: 2|uid|workDone|minutesWorked|trait,trait|job:points:spec:perk,perk:todayPoints:todayDate;job:... with each name URL-encoded. */
    String encode() {
        List<String> jobText = new ArrayList<>();
        for (Map.Entry<String, JobTrack> e : jobs.entrySet()) {
            JobTrack t = e.getValue();
            jobText.add(enc(e.getKey()) + ":" + Math.max(0, t.points) + ":" + enc(t.spec) + ":" + encList(t.perks)
                    + ":" + Math.max(0, t.todayPoints) + ":" + enc(t.todayDate));
        }
        return PREFIX + enc(uid) + "|" + Math.max(0, workDone) + "|" + Math.max(0, minutesWorked) + "|"
                + encList(traits) + "|" + String.join(";", jobText);
    }

    /** Reads encode()'s text. Anything unreadable gives a new empty record; this never throws. */
    static AgentRecord decode(String text) {
        if (text == null || !(text.startsWith(PREFIX) || text.startsWith(OLD_PREFIX))) return new AgentRecord();
        boolean old = text.startsWith(OLD_PREFIX);
        try {
            String[] parts = text.substring(PREFIX.length()).split("\\|", -1);
            if (parts.length != 5) return new AgentRecord();
            AgentRecord r = new AgentRecord();
            r.uid = dec(parts[0]);
            r.workDone = Math.max(0, Long.parseLong(parts[1]));
            r.minutesWorked = Math.max(0, Long.parseLong(parts[2]));
            r.traits.addAll(decList(parts[3]));
            if (!parts[4].isEmpty()) {
                for (String job : parts[4].split(";", -1)) {
                    String[] f = job.split(":", -1);
                    if (f.length != (old ? 4 : 6) || f[0].isEmpty()) return new AgentRecord();
                    JobTrack t = new JobTrack();
                    t.points = Math.max(0, Long.parseLong(f[1]));
                    t.spec = f[2].isEmpty() ? null : dec(f[2]);
                    t.perks.addAll(decList(f[3]));
                    if (!old) {
                        t.todayPoints = Math.max(0, Long.parseLong(f[4]));
                        t.todayDate = f[5].isEmpty() ? null : dec(f[5]);
                    }
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
