package dev.loglens;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streaming parser for the log layouts Java applications usually produce:
 * Spring Boot, Logback, Log4j 1/2, Tomcat (catalina) and java.util.logging.
 * Lines without a timestamp are folded into the previous entry (stack traces).
 */
final class LogParser {
    private static final String LEVELS =
            "TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|SEVERE|CRITICAL|FINEST|FINER|FINE|CONFIG|NOTICE";

    private static final Pattern TS_ISO = Pattern.compile(
            "^\\[?(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?"
                    + "(?:\\s?(?:Z|[+-]\\d{2}:?\\d{2})(?=[\\s\\]]))?\\]?");
    private static final Pattern TS_TOMCAT = Pattern.compile(
            "^\\[?(\\d{2})-([A-Z][a-z]{2})-(\\d{4}) (\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?\\]?");
    private static final Pattern TS_TIME = Pattern.compile(
            "^\\[?(\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?\\]?(?=\\s)");
    private static final Pattern TS_JUL = Pattern.compile(
            "^([A-Z][a-z]{2} \\d{1,2}, \\d{4} \\d{1,2}:\\d{2}:\\d{2} [AP]M)");
    private static final DateTimeFormatter JUL_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm:ss a", Locale.ENGLISH);

    private static final Pattern LEVEL = Pattern.compile("\\b(" + LEVELS + ")\\b");
    private static final Pattern LEAD_LEVEL = Pattern.compile("^\\[?(" + LEVELS + ")\\]?(?:[:\\s]|$)\\s*(.*)$");
    private static final Pattern JUL_LEVEL = Pattern.compile("^(" + LEVELS + "):\\s?(.*)$");
    private static final Pattern SPRING_PID = Pattern.compile("^\\d+\\s+---\\s+");
    private static final Pattern LOGGER = Pattern.compile(
            "^([A-Za-z_$][\\w$.]*)(?::\\d+)?(\\s+[-:]\\s+|:\\s+)(.*)$", Pattern.DOTALL);
    private static final Pattern LOGGER_BARE = Pattern.compile(
            "^((?:[a-z_][\\w$]*\\.){2,}[\\w$.]+)\\s+(.*)$", Pattern.DOTALL);
    private static final Pattern CONTINUATION = Pattern.compile(
            "^(?:\\s+|Caused by:|Suppressed:|\\.\\.\\. \\d+|Exception in thread|(?:[a-zA-Z_][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error|Throwable)).*");
    private static final String[] MONTHS =
            {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private final Consumer<LogEntry> sink;
    private final Matcher iso = TS_ISO.matcher("");
    private final Matcher tomcat = TS_TOMCAT.matcher("");
    private final Matcher timeOnly = TS_TIME.matcher("");
    private final Matcher jul = TS_JUL.matcher("");
    private final Matcher levelM = LEVEL.matcher("");

    private LogEntry cur;
    private boolean sawTs;
    private int tsEnd;
    private boolean julTs;
    private int cacheY = -1, cacheM = -1, cacheD = -1;
    private long cacheDayMs;
    private final long baseDayMs = LocalDate.now().toEpochDay() * 86_400_000L;
    private long dayOffsetMs;
    private long lastTimeOnly = -1;

    long lines;

    LogParser(Consumer<LogEntry> sink) {
        this.sink = sink;
    }

    void parse(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            lines++;
            feed(line);
        }
        flush();
    }

    private void flush() {
        if (cur != null) {
            sink.accept(cur);
            cur = null;
        }
    }

    private void feed(String line) {
        if (line.isBlank()) return;
        if (lines == 1 && line.charAt(0) == '﻿') line = line.substring(1);

        long ts = timestamp(line);
        if (ts != LogEntry.NO_TS) {
            sawTs = true;
            start(ts);
            String rest = line.substring(tsEnd);
            parseRest(rest);
            if (julTs && cur.level == Level.UNKNOWN) {
                // java.util.logging puts "class method" on the header and "LEVEL: msg" on the next line.
                cur.julHeader = true;
                String[] parts = rest.trim().split("\\s+");
                if (parts.length > 0 && !parts[0].isEmpty()) cur.logger = parts[0];
                cur.message = "";
            }
            return;
        }

        if (cur != null && cur.julHeader && cur.level == Level.UNKNOWN) {
            Matcher m = JUL_LEVEL.matcher(line);
            if (m.matches()) {
                cur.level = Level.parse(m.group(1));
                cur.message = m.group(2);
                return;
            }
        }

        if (sawTs) {
            if (cur != null) cur.addExtra(line);
            return;
        }

        // File without timestamps: every line is an entry unless it looks like a stack trace.
        if (cur != null && CONTINUATION.matcher(line).matches()) {
            cur.addExtra(line);
            return;
        }
        start(LogEntry.NO_TS);
        Matcher lead = LEAD_LEVEL.matcher(line);
        if (lead.matches()) {
            cur.level = Level.parse(lead.group(1));
            splitLogger(lead.group(2));
        } else {
            cur.message = line.trim();
        }
    }

    private void start(long ts) {
        flush();
        cur = new LogEntry();
        cur.ts = ts;
        cur.lineNo = lines;
    }

    private long timestamp(String line) {
        char c = line.charAt(0);
        julTs = false;
        if (c == '[' || (c >= '0' && c <= '9')) {
            if (iso.reset(line).lookingAt()) {
                tsEnd = iso.end();
                return dayMs(num(iso, 1), num(iso, 2), num(iso, 3))
                        + timeMs(num(iso, 4), num(iso, 5), num(iso, 6), iso.group(7));
            }
            if (tomcat.reset(line).lookingAt()) {
                int month = month(tomcat.group(2));
                if (month > 0) {
                    tsEnd = tomcat.end();
                    return dayMs(num(tomcat, 3), month, num(tomcat, 1))
                            + timeMs(num(tomcat, 4), num(tomcat, 5), num(tomcat, 6), tomcat.group(7));
                }
            }
            if (timeOnly.reset(line).lookingAt()) {
                tsEnd = timeOnly.end();
                long ms = timeMs(num(timeOnly, 1), num(timeOnly, 2), num(timeOnly, 3), timeOnly.group(4));
                if (lastTimeOnly >= 0 && ms < lastTimeOnly - 3_600_000L) dayOffsetMs += 86_400_000L; // midnight rollover
                lastTimeOnly = ms;
                return baseDayMs + dayOffsetMs + ms;
            }
        } else if (c >= 'A' && c <= 'Z' && jul.reset(line).lookingAt()) {
            try {
                LocalDateTime dt = LocalDateTime.parse(jul.group(1), JUL_FMT);
                tsEnd = jul.end();
                julTs = true;
                return dt.toEpochSecond(ZoneOffset.UTC) * 1000L;
            } catch (RuntimeException ignored) {
                // not a date after all
            }
        }
        return LogEntry.NO_TS;
    }

    private static int num(Matcher m, int group) {
        return Integer.parseInt(m.group(group));
    }

    private static int month(String abbr) {
        for (int i = 0; i < MONTHS.length; i++) if (MONTHS[i].equals(abbr)) return i + 1;
        return -1;
    }

    private long dayMs(int y, int m, int d) {
        if (y != cacheY || m != cacheM || d != cacheD) {
            try {
                cacheDayMs = LocalDate.of(y, m, d).toEpochDay() * 86_400_000L;
            } catch (RuntimeException e) {
                cacheDayMs = 0;
            }
            cacheY = y;
            cacheM = m;
            cacheD = d;
        }
        return cacheDayMs;
    }

    private static long timeMs(int h, int min, int s, String fraction) {
        long ms = 0;
        if (fraction != null) {
            String f = fraction.length() > 3 ? fraction.substring(0, 3) : fraction;
            ms = Integer.parseInt(f);
            for (int i = f.length(); i < 3; i++) ms *= 10;
        }
        return h * 3_600_000L + min * 60_000L + s * 1000L + ms;
    }

    /** Parses everything after the timestamp: level, thread, logger, message. */
    private void parseRest(String rest) {
        levelM.reset(rest);
        if (!levelM.find() || levelM.start() > 64) {
            cur.message = rest.trim();
            return;
        }
        cur.level = Level.parse(levelM.group(1));
        String pre = rest.substring(0, levelM.start());
        String post = rest.substring(levelM.end());

        int close = pre.lastIndexOf(']');
        if (close > 0) {
            int open = pre.lastIndexOf('[', close);
            if (open >= 0 && close - open > 1) cur.thread = pre.substring(open + 1, close).trim();
        }

        int i = 0;
        while (i < post.length() && (post.charAt(i) == ']' || post.charAt(i) == ':' || Character.isWhitespace(post.charAt(i)))) i++;
        post = post.substring(i);
        Matcher pid = SPRING_PID.matcher(post);
        if (pid.lookingAt()) post = post.substring(pid.end());
        while (post.startsWith("[")) {
            int end = post.indexOf(']');
            if (end < 0) break;
            String group = post.substring(1, end).trim();
            if (!group.isEmpty()) cur.thread = group;
            post = post.substring(end + 1).stripLeading();
        }
        if (post.startsWith("- ") || post.startsWith(": ")) {
            cur.message = post.substring(2).trim();
            return;
        }
        splitLogger(post);
    }

    private void splitLogger(String text) {
        Matcher m = LOGGER.matcher(text);
        if (m.matches() && (m.group(1).indexOf('.') >= 0 || Character.isWhitespace(m.group(2).charAt(0)))) {
            cur.logger = trimDots(m.group(1));
            cur.message = m.group(3).trim();
            return;
        }
        m = LOGGER_BARE.matcher(text);
        if (m.matches()) {
            cur.logger = trimDots(m.group(1));
            cur.message = m.group(2).trim();
            return;
        }
        cur.message = text.trim();
    }

    private static String trimDots(String s) {
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
