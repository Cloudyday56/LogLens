package dev.loglens;

import java.util.ArrayList;
import java.util.List;

/** One logical log event: the header line plus any continuation lines (stack traces). */
final class LogEntry {
    static final long NO_TS = Long.MIN_VALUE;
    static final int MAX_EXTRA = 300;

    /** Milliseconds since epoch, interpreted exactly as written in the log (no zone shifting). */
    long ts = NO_TS;
    Level level = Level.UNKNOWN;
    String thread;
    String logger;
    String message = "";
    List<String> extra;
    int extraCount;
    long lineNo;
    boolean julHeader;

    void addExtra(String line) {
        extraCount++;
        if (extra == null) extra = new ArrayList<>();
        if (extra.size() < MAX_EXTRA) extra.add(line);
    }

    boolean hasTs() {
        return ts != NO_TS;
    }
}
