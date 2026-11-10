package dev.loglens;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.loglens.Style.*;

/** Renders the analysis as a terminal dashboard. */
final class Dashboard {
    private static final Pattern PLACEHOLDER = Pattern.compile("<(?:n|uuid|ip|hex)>");

    private final Analyzer a;
    private final int cw; // content width (inside the 1-column margin)
    private final int top;
    private final boolean twoCol;
    private final StringBuilder out = new StringBuilder();

    String source = "";
    long sizeBytes = -1;
    long totalLines;
    long totalEntries;
    long elapsedMs;
    String filterSummary;

    Dashboard(Analyzer a, int width, int top) {
        this.a = a;
        this.cw = width - 2;
        this.top = top;
        this.twoCol = cw >= 96;
    }

    String render() {
        banner();
        if (a.entries == 0) {
            line("");
            line(c(AMBER, "  No log entries " + (filterSummary != null ? "matched the filters (" + filterSummary + ")." : "found.")));
            line(c(MUTED, "  " + num(totalLines) + " lines read from " + source));
            line("");
            return out.toString();
        }
        row("Overview", null, this::overview, "Levels", null, this::levels);
        if (a.withTs >= 8 && a.spanMs() > 0) timeline();
        if (!a.exceptions.isEmpty()) {
            emit(box("Top exceptions", shown(a.exceptions.size(), "type"), exceptions(cw - 4), cw, RED));
        }
        if (!a.problems.isEmpty()) {
            emit(box("Recurring problems", shown(a.problems.size(), "pattern"), problems(cw - 4), cw, AMBER));
        }
        boolean hasLoggers = !a.loggers.isEmpty(), hasThreads = !a.threads.isEmpty();
        if (hasLoggers && hasThreads) {
            row("Loggers", shown(a.loggers.size(), null), iw -> counters(a.top(a.loggers, top), iw, true),
                    "Threads", shown(a.threads.size(), null), iw -> counters(a.top(a.threads, top), iw, false));
        } else if (hasLoggers) {
            emit(box("Loggers", shown(a.loggers.size(), null), counters(a.top(a.loggers, top), cw - 4, true), cw, VIOLET));
        } else if (hasThreads) {
            emit(box("Threads", shown(a.threads.size(), null), counters(a.top(a.threads, top), cw - 4, false), cw, VIOLET));
        }
        emit(box("Insights", null, insights(), cw, TEAL));
        line(c(FAINT, " " + trunc(Main.NAME + " " + Main.VERSION + "  ·  " + num(totalLines) + " lines in " + duration(elapsedMs)
                + "  ·  try --list --level ERROR to read the entries, --help for more", cw)));
        return out.toString();
    }

    // ---------------------------------------------------------------- sections

    private void banner() {
        String[] art = {
                "╻  ┏━┓┏━╸╻  ┏━╸┏┓╻┏━┓",
                "┃  ┃ ┃┃╺┓┃  ┣╸ ┃┗┫┗━┓",
                "┗━╸┗━┛┗━┛┗━╸┗━╸╹ ╹┗━┛"};
        String[] side = {
                bold("Java log analysis") + c(MUTED, "  v" + Main.VERSION),
                c(MUTED, trunc(source, Math.max(10, cw - 30))),
                filterSummary != null ? c(AMBER, "filter: ") + c(MUTED, trunc(filterSummary, Math.max(10, cw - 38))) : ""};
        line("");
        for (int i = 0; i < art.length; i++) line("  " + gradient(art[i]) + "   " + side[i]);
        line("");
    }

    private List<String> overview(int iw) {
        List<String> l = new ArrayList<>();
        l.add(kv("Health", badge(a.healthColor, " " + a.health + " ")));
        l.add(kv("Entries", bold(num(a.entries))
                + (totalEntries != a.entries ? c(MUTED, " of " + num(totalEntries) + " (filtered)") : "")));
        l.add(kv("Lines", num(totalLines) + (sizeBytes >= 0 ? c(MUTED, "  ·  " + bytes(sizeBytes)) : "")));
        if (a.withTs > 0) {
            l.add(kv("From", stamp(a.minTs)));
            l.add(kv("To", stamp(a.maxTs)));
            long span = a.spanMs();
            String rate = "";
            if (span >= 1000) {
                double perMin = a.withTs / (span / 60_000.0);
                rate = c(MUTED, "  ·  " + (perMin >= 120 ? String.format("%.1f/s", perMin / 60) : String.format("%.1f/min", perMin)));
            }
            l.add(kv("Duration", duration(span) + rate));
        } else {
            l.add(kv("Time range", c(MUTED, "no timestamps found")));
        }
        long errors = a.errorCount();
        l.add(kv("Errors", (errors > 0 ? b(RED, num(errors)) : c(GREEN, "0"))
                + c(MUTED, "  ·  " + pct(errors, a.entries) + " of entries")));
        l.add(kv("Exceptions", num(a.exceptions.values().stream().mapToLong(s -> s.count).sum())
                + c(MUTED, "  ·  " + num(a.exceptions.size()) + (a.exceptions.size() == 1 ? " type" : " types") + "  ·  "
                        + num(a.stackTraces) + (a.stackTraces == 1 ? " stack trace" : " stack traces"))));
        return l;
    }

    private String stamp(long ts) {
        return c(MUTED, date(ts) + " ") + timeMs(ts);
    }

    private static String kv(String key, String value) {
        return c(MUTED, pad(key, 12)) + value;
    }

    private List<String> levels(int iw) {
        List<String> l = new ArrayList<>();
        long max = 1;
        for (long n : a.levels) max = Math.max(max, n);
        int barW = Math.max(6, iw - 27);
        Level[] order = {Level.FATAL, Level.ERROR, Level.WARN, Level.INFO, Level.DEBUG, Level.TRACE, Level.UNKNOWN};
        for (Level lv : order) {
            long n = a.levels[lv.ordinal()];
            if (n == 0 && (lv == Level.UNKNOWN || lv == Level.FATAL || lv == Level.TRACE)) continue;
            String label = lv == Level.UNKNOWN ? "OTHER" : lv.name();
            l.add((n > 0 ? b(lv.color(), pad(label, 5)) : c(FAINT, pad(label, 5))) + "  "
                    + bar((double) n / max, barW, lv.color()) + "  "
                    + (n > 0 ? lpad(num(n), 10) : c(FAINT, lpad("0", 10)))
                    + c(MUTED, lpad(pct(n, a.entries), 8)));
        }
        return l;
    }

    private void timeline() {
        int iw = cw - 4;
        int gutter = 8;
        int cols = iw - gutter;
        int height = 7;
        long[][] t = a.timeline(cols);
        long max = 1, maxWarn = 0, maxErr = 0;
        for (int i = 0; i < cols; i++) {
            max = Math.max(max, t[0][i]);
            maxWarn = Math.max(maxWarn, t[1][i]);
            maxErr = Math.max(maxErr, t[2][i]);
        }
        int[] scaled = new int[cols];
        for (int i = 0; i < cols; i++) {
            scaled[i] = t[0][i] == 0 ? 0 : (int) Math.max(1, Math.round(t[0][i] * height * 8.0 / max));
        }

        List<String> l = new ArrayList<>();
        for (int r = height - 1; r >= 0; r--) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cols; i++) sb.append(sparkEighth(scaled[i] - r * 8));
            String label = r == height - 1 ? compact(max) : r == height / 2 && max >= 8 ? compact(Math.round(max * (r + 1.0) / height)) : "";
            l.add(c(MUTED, lpad(label, gutter - 2)) + c(BORDER, label.isEmpty() ? " │" : " ┤")
                    + c(lerp(0x3B82F6, ACCENT, (double) r / (height - 1)), sb.toString()));
        }

        // time axis with tick labels
        int labels = cols >= 90 ? 5 : cols >= 50 ? 3 : 2;
        char[] axis = repeat("─", cols).toCharArray();
        char[] text = repeat(" ", cols).toCharArray();
        int lastEnd = -2;
        for (int k = 0; k < labels; k++) {
            int pos = (int) Math.round((double) k * (cols - 1) / (labels - 1));
            long ts = a.minTs + Math.round((double) a.spanMs() * pos / (cols - 1));
            String s = a.multiDay() ? dayTime(ts) : time(ts);
            int start = Math.max(0, Math.min(cols - s.length(), pos - s.length() / 2));
            if (start <= lastEnd + 1) continue;
            axis[pos] = '┬';
            s.getChars(0, s.length(), text, start);
            lastEnd = start + s.length() - 1;
        }
        l.add(c(MUTED, lpad("0", gutter - 2)) + c(BORDER, " └" + new String(axis)));
        if (maxErr > 0) l.add(sparkRow("errors", t[2], maxErr, RED, gutter));
        if (maxWarn > 0) l.add(sparkRow("warns", t[1], maxWarn, AMBER, gutter));
        l.add(repeat(" ", gutter) + c(MUTED, new String(text)));

        String sub = "entries over time  ·  1 column ≈ " + duration(Math.max(1, (a.spanMs() + 1) / cols));
        emit(box("Timeline", sub, l, cw, ACCENT));
    }

    private static String sparkRow(String label, long[] values, long max, int rgb, int gutter) {
        StringBuilder sb = new StringBuilder();
        for (long v : values) sb.append(spark(v, max));
        return c(rgb, lpad(label, gutter - 2)) + "  " + c(rgb, sb.toString());
    }

    private List<String> exceptions(int iw) {
        List<String> l = new ArrayList<>();
        List<Analyzer.ExStat> list = a.topExceptions(top);
        long max = list.isEmpty() ? 1 : list.get(0).count;
        int barW = 12;
        String indent = repeat(" ", 3 + barW + 2 + 7 + 2);
        int textW = iw - indent.length();
        int rank = 1;
        for (Analyzer.ExStat s : list) {
            if (rank > 1) l.add("");
            l.add(c(MUTED, lpad(rank + "", 2)) + " " + bar((double) s.count / max, barW, RED) + "  "
                    + bold(lpad(num(s.count) + "×", 7)) + "  " + b(RED, abbreviate(s.type, textW)));
            if (s.sample != null) l.add(indent + trunc(s.sample, textW));
            StringBuilder meta = new StringBuilder();
            if (s.first != LogEntry.NO_TS) {
                meta.append(s.first == s.last ? a.when(s.first) : a.when(s.first) + " → " + a.when(s.last));
            }
            String root = s.topRoot();
            if (root != null) meta.append(meta.length() > 0 ? "  ·  " : "").append("caused by ").append(abbreviate(root, 48));
            String frame = s.topFrame();
            if (frame != null) meta.append(meta.length() > 0 ? "  ·  " : "").append("at ").append(shortFrame(frame));
            if (meta.length() > 0) l.add(indent + c(MUTED, trunc(meta.toString(), textW)));
            rank++;
        }
        return l;
    }

    /** com.acme.db.Pool.acquire(Pool.java:88) -> Pool.acquire(Pool.java:88) */
    private static String shortFrame(String frame) {
        int paren = frame.indexOf('(');
        if (paren < 0) return frame;
        String method = frame.substring(0, paren);
        int dot = method.lastIndexOf('.');
        if (dot > 0) dot = method.lastIndexOf('.', dot - 1);
        return (dot >= 0 ? method.substring(dot + 1) : method) + frame.substring(paren);
    }

    private List<String> problems(int iw) {
        List<String> l = new ArrayList<>();
        int textW = iw - 16;
        for (Analyzer.MsgStat s : a.topProblems(top)) {
            String label = s.level == Level.UNKNOWN ? "OTHER" : s.level.name();
            String where = s.logger != null && iw >= 100 ? abbreviate(s.logger, 24) : "";
            String seen = s.first == LogEntry.NO_TS ? "" : s.first == s.last ? a.when(s.first) : a.when(s.first) + " → " + a.when(s.last);
            String meta = where + (!where.isEmpty() && !seen.isEmpty() ? "  " : "") + seen;
            int msgW = Math.max(20, textW - (meta.isEmpty() ? 0 : meta.length() + 2));
            String msg = s.pattern;
            if (msg.length() > msgW) {
                msg = msg.substring(0, msgW - 1);
                int open = msg.lastIndexOf('<'); // never cut a placeholder in half
                if (open > msg.lastIndexOf('>')) msg = msg.substring(0, open);
                msg = msg.stripTrailing() + "…";
            }
            l.add(bold(lpad(num(s.count) + "×", 7)) + "  " + b(s.level.color(), pad(label, 5)) + "  "
                    + pad(paintPlaceholders(msg), msgW) + "  " + c(MUTED, meta));
        }
        return l;
    }

    private static String paintPlaceholders(String msg) {
        if (!color) return msg;
        Matcher m = PLACEHOLDER.matcher(msg);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(msg, last, m.start()).append(c(VIOLET, m.group()));
            last = m.end();
        }
        return sb.append(msg.substring(last)).toString();
    }

    private List<String> counters(List<Analyzer.Counter> list, int iw, boolean abbreviateNames) {
        List<String> l = new ArrayList<>();
        long max = list.isEmpty() ? 1 : list.get(0).count;
        int barW = Math.max(6, Math.min(16, iw / 6));
        int nameW = iw - barW - 2 - 7 - 1 - 9;
        for (Analyzer.Counter ct : list) {
            String name = abbreviateNames ? abbreviate(ct.name, nameW - 1) : trunc(ct.name, nameW - 1);
            String problems = ct.errors > 0 ? c(RED, lpad(compact(ct.errors) + " err", 9))
                    : ct.warns > 0 ? c(AMBER, lpad(compact(ct.warns) + " warn", 9)) : repeat(" ", 9);
            l.add(pad(name, nameW) + bar((double) ct.count / max, barW, ct.errors > 0 ? RED : ct.warns > 0 ? AMBER : VIOLET)
                    + "  " + lpad(ct.count > 999_999 ? compact(ct.count) : num(ct.count), 7) + " " + problems);
        }
        return l;
    }

    private List<String> insights() {
        List<String> l = new ArrayList<>();
        for (Analyzer.Insight in : a.insights) {
            int rgb = in.severity == 2 ? RED : in.severity == 1 ? AMBER : in.severity < 0 ? GREEN : ACCENT;
            String glyph = in.severity == 2 ? "▲" : in.severity == 1 ? "◆" : in.severity < 0 ? "✔" : "●";
            boolean first = true;
            for (String part : wrap(in.text, cw - 6)) {
                l.add((first ? c(rgb, glyph) : " ") + " " + part);
                first = false;
            }
        }
        return l;
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            if (cur.length() > 0 && cur.length() + 1 + word.length() > width) {
                lines.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    private String shown(int total, String noun) {
        String what = noun == null ? "" : " " + noun + (total == 1 ? "" : "s");
        return total > top ? "top " + top + " of " + num(total) + what : num(total) + (noun == null ? " total" : what);
    }

    // ---------------------------------------------------------------- layout

    private interface Content {
        List<String> lines(int innerWidth);
    }

    private void row(String t1, String s1, Content left, String t2, String s2, Content right) {
        if (!twoCol) {
            emit(box(t1, s1, left.lines(cw - 4), cw, ACCENT));
            emit(box(t2, s2, right.lines(cw - 4), cw, VIOLET));
            return;
        }
        int wl = (cw - 1) / 2, wr = cw - 1 - wl;
        List<String> a1 = new ArrayList<>(left.lines(wl - 4)), b1 = new ArrayList<>(right.lines(wr - 4));
        while (a1.size() < b1.size()) a1.add("");
        while (b1.size() < a1.size()) b1.add("");
        List<String> lb = box(t1, s1, a1, wl, ACCENT), rb = box(t2, s2, b1, wr, VIOLET);
        for (int i = 0; i < lb.size(); i++) line(" " + lb.get(i) + " " + rb.get(i));
    }

    private static List<String> box(String title, String sub, List<String> lines, int width, int accent) {
        List<String> o = new ArrayList<>();
        int fill = width - 5 - title.length();
        String right = "";
        if (sub != null && sub.length() + 3 <= fill - 2) {
            right = " " + sub + " ";
            fill -= right.length() + 1;
            o.add(c(BORDER, "╭─ ") + b(accent, title) + " " + c(BORDER, repeat("─", fill)) + c(MUTED, right) + c(BORDER, "─╮"));
        } else {
            o.add(c(BORDER, "╭─ ") + b(accent, title) + " " + c(BORDER, repeat("─", fill) + "╮"));
        }
        String side = c(BORDER, "│");
        for (String s : lines) o.add(side + " " + fit(s, width - 4) + " " + side);
        o.add(c(BORDER, "╰" + repeat("─", width - 2) + "╯"));
        return o;
    }

    private void emit(List<String> lines) {
        for (String s : lines) line(" " + s);
    }

    private void line(String s) {
        out.append(s).append('\n');
    }
}
