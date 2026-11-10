package dev.loglens;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Aggregates entries into the statistics shown on the dashboard. */
final class Analyzer {
    private static final int MAX_KEYS = 50_000;
    private static final Pattern EXC = Pattern.compile(
            "\\b((?:[a-zA-Z_][\\w$]*\\.)*[A-Z][\\w$]*(?:Exception|Error|Throwable))\\b(?::\\s*(.*))?");
    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern IP = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b");
    private static final Pattern HEX = Pattern.compile("\\b0x[0-9a-fA-F]+\\b|\\b(?=[0-9a-f]*\\d)(?=[0-9a-f]*[a-f])[0-9a-f]{10,}\\b");
    private static final Pattern NUM = Pattern.compile("\\d+(?:[.,]\\d+)*");

    static final class Counter {
        final String name;
        long count, warns, errors;

        Counter(String name) {
            this.name = name;
        }
    }

    static final class ExStat {
        final String type;
        long count;
        long first = LogEntry.NO_TS, last = LogEntry.NO_TS;
        String sample;
        final Map<String, Integer> roots = new HashMap<>();
        final Map<String, Integer> frames = new HashMap<>();

        ExStat(String type) {
            this.type = type;
        }

        String topRoot() {
            return topKey(roots);
        }

        String topFrame() {
            return topKey(frames);
        }
    }

    static final class MsgStat {
        final String pattern;
        Level level;
        long count;
        long first = LogEntry.NO_TS, last = LogEntry.NO_TS;
        String logger;

        MsgStat(String pattern) {
            this.pattern = pattern;
        }
    }

    static final class Insight {
        final int severity; // 0 = info, 1 = warning, 2 = critical, -1 = good
        final String text;

        Insight(int severity, String text) {
            this.severity = severity;
            this.text = text;
        }
    }

    long entries, withTs, stackTraces, continuationLines;
    final long[] levels = new long[Level.values().length];
    long minTs = Long.MAX_VALUE, maxTs = Long.MIN_VALUE;
    long firstErrorTs = LogEntry.NO_TS, lastErrorTs = LogEntry.NO_TS;

    final Map<String, Counter> loggers = new HashMap<>();
    final Map<String, Counter> threads = new HashMap<>();
    final Map<String, ExStat> exceptions = new HashMap<>();
    final Map<String, MsgStat> problems = new HashMap<>();
    final List<Insight> insights = new ArrayList<>();

    private long[] times = new long[4096];
    private byte[] timeLevels = new byte[4096];
    private int timeCount;

    // derived in finish()
    long peakMinute = LogEntry.NO_TS, peakMinuteCount;
    long errorPeakMinute = LogEntry.NO_TS, errorPeakCount;
    long longestGapMs, longestGapStart = LogEntry.NO_TS;
    String health = "HEALTHY";
    int healthColor = Style.GREEN;

    void accept(LogEntry e) {
        entries++;
        levels[e.level.ordinal()]++;
        continuationLines += e.extraCount;
        boolean warn = e.level == Level.WARN;
        boolean error = e.level.ordinal() >= Level.ERROR.ordinal();

        if (e.hasTs()) {
            withTs++;
            if (e.ts < minTs) minTs = e.ts;
            if (e.ts > maxTs) maxTs = e.ts;
            if (timeCount == times.length) {
                times = Arrays.copyOf(times, timeCount * 2);
                timeLevels = Arrays.copyOf(timeLevels, timeCount * 2);
            }
            times[timeCount] = e.ts;
            timeLevels[timeCount++] = (byte) e.level.ordinal();
            if (error) {
                if (firstErrorTs == LogEntry.NO_TS || e.ts < firstErrorTs) firstErrorTs = e.ts;
                if (lastErrorTs == LogEntry.NO_TS || e.ts > lastErrorTs) lastErrorTs = e.ts;
            }
        }

        if (e.logger != null) bump(loggers, e.logger, warn, error);
        if (e.thread != null) bump(threads, e.thread, warn, error);
        if (warn || error) recordProblem(e);
        if (e.extraCount > 0 || error || warn) recordException(e);
    }

    private static void bump(Map<String, Counter> map, String key, boolean warn, boolean error) {
        Counter c = map.get(key);
        if (c == null) {
            if (map.size() >= MAX_KEYS) return;
            map.put(key, c = new Counter(key));
        }
        c.count++;
        if (warn) c.warns++;
        if (error) c.errors++;
    }

    private void recordProblem(LogEntry e) {
        String key = normalize(e.message);
        if (key.isEmpty()) return;
        MsgStat s = problems.get(key);
        if (s == null) {
            if (problems.size() >= MAX_KEYS) return;
            problems.put(key, s = new MsgStat(key));
            s.level = e.level;
            s.logger = e.logger;
        }
        if (e.level.ordinal() > s.level.ordinal()) s.level = e.level;
        s.count++;
        if (e.hasTs()) {
            if (s.first == LogEntry.NO_TS || e.ts < s.first) s.first = e.ts;
            if (s.last == LogEntry.NO_TS || e.ts > s.last) s.last = e.ts;
        }
    }

    /** Collapses variable parts (ids, numbers, addresses) so similar messages group together. */
    static String normalize(String message) {
        String s = message.length() > 300 ? message.substring(0, 300) : message;
        s = UUID.matcher(s).replaceAll("<uuid>");
        s = IP.matcher(s).replaceAll("<ip>");
        s = HEX.matcher(s).replaceAll("<hex>");
        s = NUM.matcher(s).replaceAll("<n>");
        return s.trim();
    }

    private void recordException(LogEntry e) {
        String primary = null, primaryMsg = null, root = null, frame = null;
        boolean hasFrames = false;
        if (e.extra != null) {
            for (String line : e.extra) {
                String t = line.trim();
                if (t.startsWith("at ")) {
                    hasFrames = true;
                    if (frame == null) frame = t.substring(3);
                } else if (t.startsWith("Caused by:")) {
                    Matcher m = EXC.matcher(t);
                    if (m.find()) root = m.group(1);
                } else if (primary == null && !t.startsWith("...") && !t.startsWith("Suppressed:")) {
                    Matcher m = EXC.matcher(t);
                    if (m.find() && (m.start() == 0 || t.startsWith("Exception in thread"))) {
                        primary = m.group(1);
                        primaryMsg = m.group(2);
                    }
                }
            }
        }
        if (primary == null) {
            Matcher m = EXC.matcher(e.message);
            if (m.find()) {
                primary = m.group(1);
                primaryMsg = m.group(2);
            }
        }
        if (primary == null) primary = root;
        if (hasFrames) stackTraces++;
        if (primary == null) return;

        ExStat s = exceptions.get(primary);
        if (s == null) {
            if (exceptions.size() >= MAX_KEYS) return;
            exceptions.put(primary, s = new ExStat(primary));
        }
        s.count++;
        if (s.sample == null && primaryMsg != null && !primaryMsg.isBlank()) s.sample = primaryMsg.trim();
        if (root != null && !root.equals(primary)) s.roots.merge(root, 1, Integer::sum);
        if (frame != null && s.frames.size() < 200) s.frames.merge(frame, 1, Integer::sum);
        if (e.hasTs()) {
            if (s.first == LogEntry.NO_TS || e.ts < s.first) s.first = e.ts;
            if (s.last == LogEntry.NO_TS || e.ts > s.last) s.last = e.ts;
        }
    }

    private static String topKey(Map<String, Integer> map) {
        String best = null;
        int max = 0;
        for (Map.Entry<String, Integer> en : map.entrySet()) {
            if (en.getValue() > max) {
                max = en.getValue();
                best = en.getKey();
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- queries

    long errorCount() {
        return levels[Level.ERROR.ordinal()] + levels[Level.FATAL.ordinal()];
    }

    long spanMs() {
        return withTs == 0 ? 0 : maxTs - minTs;
    }

    boolean multiDay() {
        return withTs > 0 && !Style.date(minTs).equals(Style.date(maxTs));
    }

    /** Formats a timestamp at the precision that makes sense for this log's span. */
    String when(long ts) {
        if (ts == LogEntry.NO_TS) return "-";
        return multiDay() ? Style.dayTime(ts) : Style.time(ts);
    }

    List<Counter> top(Map<String, Counter> map, int n) {
        List<Counter> list = new ArrayList<>(map.values());
        list.sort(Comparator.comparingLong((Counter c) -> c.count).reversed().thenComparing(c -> c.name));
        return list.subList(0, Math.min(n, list.size()));
    }

    List<ExStat> topExceptions(int n) {
        List<ExStat> list = new ArrayList<>(exceptions.values());
        list.sort(Comparator.comparingLong((ExStat s) -> s.count).reversed().thenComparing(s -> s.type));
        return list.subList(0, Math.min(n, list.size()));
    }

    List<MsgStat> topProblems(int n) {
        List<MsgStat> list = new ArrayList<>(problems.values());
        list.sort(Comparator.comparingInt((MsgStat s) -> Math.min(s.level.ordinal(), Level.ERROR.ordinal())).reversed()
                .thenComparing(Comparator.comparingLong((MsgStat s) -> s.count).reversed())
                .thenComparing(s -> s.pattern));
        return list.subList(0, Math.min(n, list.size()));
    }

    /** Returns [all, warnings, errors] counts bucketed into {@code cols} equal time slices. */
    long[][] timeline(int cols) {
        long[][] t = new long[3][cols];
        long span = spanMs() + 1;
        for (int i = 0; i < timeCount; i++) {
            int bucket = (int) ((times[i] - minTs) * cols / span);
            t[0][bucket]++;
            if (timeLevels[i] == Level.WARN.ordinal()) t[1][bucket]++;
            else if (timeLevels[i] >= Level.ERROR.ordinal()) t[2][bucket]++;
        }
        return t;
    }

    // ---------------------------------------------------------------- insights

    void finish() {
        if (entries == 0) return;
        long errors = errorCount();
        long fatal = levels[Level.FATAL.ordinal()];
        long warns = levels[Level.WARN.ordinal()];
        double errorRate = (double) errors / entries;

        if (timeCount > 1) analyzeTime();

        if (errorRate >= 0.05) {
            health = "CRITICAL";
            healthColor = Style.RED;
        } else if (errorRate >= 0.01 || fatal > 0) {
            health = "DEGRADED";
            healthColor = Style.AMBER;
        } else if (errors > 0 || (double) warns / entries >= 0.05) {
            health = "WATCH";
            healthColor = Style.ACCENT;
        }

        if (errors == 0) {
            insights.add(new Insight(-1, "No ERROR or FATAL entries found."));
        } else {
            insights.add(new Insight(errorRate >= 0.05 ? 2 : errorRate >= 0.01 ? 1 : 0,
                    Style.pct(errors, entries) + " of entries are ERROR or worse (" + Style.num(errors)
                            + " of " + Style.num(entries) + ")"
                            + (firstErrorTs == LogEntry.NO_TS ? "" : firstErrorTs == lastErrorTs ? ", at " + when(firstErrorTs)
                            : ", first at " + when(firstErrorTs) + ", last at " + when(lastErrorTs)) + "."));
        }
        if (fatal > 0) {
            insights.add(new Insight(2, Style.num(fatal) + " FATAL " + (fatal == 1 ? "entry" : "entries")
                    + " - the application hit an unrecoverable condition."));
        }
        if (errorPeakCount >= 5 && spanMs() > 180_000) {
            double avg = errors / Math.max(1.0, spanMs() / 60_000.0);
            if (errorPeakCount >= avg * 3) {
                insights.add(new Insight(2, "Error spike at " + when(errorPeakMinute) + ": " + Style.num(errorPeakCount)
                        + " errors in one minute (" + String.format("%.0fx", errorPeakCount / Math.max(avg, 0.01)) + " the average rate)."));
            }
        }
        List<ExStat> ex = topExceptions(1);
        if (!ex.isEmpty()) {
            long total = exceptions.values().stream().mapToLong(s -> s.count).sum();
            ExStat s = ex.get(0);
            if (exceptions.size() > 1 && s.count * 2 >= total) {
                insights.add(new Insight(1, Style.abbreviate(s.type, 60) + " accounts for " + Style.pct(s.count, total)
                        + " of all exceptions" + (s.topRoot() != null ? ", usually caused by " + Style.abbreviate(s.topRoot(), 50) : "") + "."));
            }
        }
        if (peakMinute != LogEntry.NO_TS && spanMs() > 180_000) {
            insights.add(new Insight(0, "Busiest minute was " + when(peakMinute) + " with " + Style.num(peakMinuteCount) + " entries."));
        }
        if (longestGapStart != LogEntry.NO_TS) {
            insights.add(new Insight(1, "Longest silence: " + Style.duration(longestGapMs) + " with no output, starting at "
                    + when(longestGapStart) + "."));
        }
        List<Counter> noisy = top(loggers, 1);
        if (!noisy.isEmpty() && loggers.size() > 2 && noisy.get(0).count * 10 >= entries * 4) {
            insights.add(new Insight(0, Style.abbreviate(noisy.get(0).name, 50) + " produces "
                    + Style.pct(noisy.get(0).count, entries) + " of all output."));
        }
        long unknown = levels[Level.UNKNOWN.ordinal()];
        if (unknown > 0) {
            insights.add(new Insight(0, Style.num(unknown) + " " + (unknown == 1 ? "entry has" : "entries have") + " no recognisable log level."));
        }
    }

    private void analyzeTime() {
        Map<Long, long[]> minutes = new HashMap<>();
        for (int i = 0; i < timeCount; i++) {
            long[] m = minutes.computeIfAbsent(Math.floorDiv(times[i], 60_000L), k -> new long[2]);
            m[0]++;
            if (timeLevels[i] >= Level.ERROR.ordinal()) m[1]++;
        }
        for (Map.Entry<Long, long[]> en : minutes.entrySet()) {
            long minute = en.getKey() * 60_000L;
            long[] m = en.getValue();
            if (m[0] > peakMinuteCount || (m[0] == peakMinuteCount && minute < peakMinute)) {
                peakMinuteCount = m[0];
                peakMinute = minute;
            }
            if (m[1] > errorPeakCount || (m[1] == errorPeakCount && m[1] > 0 && minute < errorPeakMinute)) {
                errorPeakCount = m[1];
                errorPeakMinute = minute;
            }
        }

        long[] sorted = Arrays.copyOf(times, timeCount);
        Arrays.sort(sorted);
        long gap = 0, gapStart = 0;
        for (int i = 1; i < sorted.length; i++) {
            long d = sorted[i] - sorted[i - 1];
            if (d > gap) {
                gap = d;
                gapStart = sorted[i - 1];
            }
        }
        double avgInterval = (double) spanMs() / (timeCount - 1);
        if (gap >= 10_000 && gap >= avgInterval * 20) {
            longestGapMs = gap;
            longestGapStart = gapStart;
        }
    }
}
