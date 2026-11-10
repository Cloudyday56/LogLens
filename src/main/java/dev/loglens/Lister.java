package dev.loglens;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.loglens.Style.*;

/** Prints matching entries as a colourised, aligned listing (--list). */
final class Lister {
    private final PrintStream out;
    private final Pattern grep;
    private final int head, tail;
    private final ArrayDeque<LogEntry> buffer = new ArrayDeque<>();
    private long printed;
    long matched;

    Lister(PrintStream out, Pattern grep, int head, int tail) {
        this.out = out;
        this.grep = grep;
        this.head = head;
        this.tail = tail;
    }

    void accept(LogEntry e) {
        matched++;
        if (tail > 0) {
            buffer.addLast(e);
            if (buffer.size() > tail) buffer.removeFirst();
        } else if (head <= 0 || printed < head) {
            print(e);
        }
    }

    void finish() {
        for (LogEntry e : buffer) print(e);
        buffer.clear();
        long hidden = matched - printed;
        out.println();
        out.println(c(MUTED, " " + num(matched) + (matched == 1 ? " entry" : " entries") + " matched"
                + (hidden > 0 ? "  ·  showing " + num(printed) + (tail > 0 ? " (last)" : " (first)") : "")));
    }

    private void print(LogEntry e) {
        printed++;
        StringBuilder sb = new StringBuilder(" ");
        if (e.hasTs()) sb.append(c(MUTED, date(e.ts).substring(5) + " " + timeMs(e.ts))).append("  ");
        String label = e.level == Level.UNKNOWN ? "-" : e.level.name();
        sb.append(b(e.level.color(), pad(label, 5))).append("  ");
        if (e.logger != null) sb.append(c(VIOLET, pad(abbreviate(e.logger, 28), 28))).append("  ");
        String msg = mark(e.message);
        sb.append(e.level.ordinal() >= Level.ERROR.ordinal() ? bold(msg) : msg);
        if (e.thread != null) sb.append(c(FAINT, "  [" + e.thread + "]"));
        out.println(sb);
        if (e.extra != null) {
            for (String line : e.extra) {
                String t = line.trim();
                boolean frame = t.startsWith("at ") || t.startsWith("...");
                out.println(c(BORDER, "   │ ") + (frame ? c(FAINT, "  " + t) : c(RED, t)));
            }
            if (e.extraCount > e.extra.size()) {
                out.println(c(BORDER, "   │ ") + c(FAINT, "  … " + num(e.extraCount - e.extra.size()) + " more lines"));
            }
        }
    }

    private String mark(String msg) {
        if (grep == null || !color) return msg;
        Matcher m = grep.matcher(msg);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            if (m.end() == m.start()) continue;
            sb.append(msg, last, m.start()).append(highlight(m.group()));
            last = m.end();
        }
        return sb.append(msg.substring(last)).toString();
    }
}
