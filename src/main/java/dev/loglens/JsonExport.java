package dev.loglens;

import java.util.List;

/** Machine-readable version of the report (--json). */
final class JsonExport {
    private final StringBuilder sb = new StringBuilder();

    static String render(Analyzer a, String source, long lines, long totalEntries, int top) {
        JsonExport j = new JsonExport();
        StringBuilder sb = j.sb;
        sb.append("{\n");
        j.field("source", source);
        j.field("lines", lines);
        j.field("entries", a.entries);
        j.field("entriesBeforeFilter", totalEntries);
        j.field("health", a.health);
        if (a.withTs > 0) {
            j.field("from", Style.dateTime(a.minTs).toString());
            j.field("to", Style.dateTime(a.maxTs).toString());
            j.field("durationMs", a.spanMs());
        }
        j.field("stackTraces", a.stackTraces);

        sb.append("  \"levels\": {");
        boolean first = true;
        for (Level lv : Level.values()) {
            sb.append(first ? "" : ", ").append(quote(lv.name())).append(": ").append(a.levels[lv.ordinal()]);
            first = false;
        }
        sb.append("},\n");

        sb.append("  \"exceptions\": [");
        List<Analyzer.ExStat> ex = a.topExceptions(top);
        for (int i = 0; i < ex.size(); i++) {
            Analyzer.ExStat s = ex.get(i);
            sb.append(i == 0 ? "\n" : ",\n").append("    {\"type\": ").append(quote(s.type))
                    .append(", \"count\": ").append(s.count)
                    .append(", \"message\": ").append(quote(s.sample))
                    .append(", \"rootCause\": ").append(quote(s.topRoot()))
                    .append(", \"topFrame\": ").append(quote(s.topFrame()))
                    .append(", \"first\": ").append(ts(s.first))
                    .append(", \"last\": ").append(ts(s.last)).append("}");
        }
        sb.append(ex.isEmpty() ? "],\n" : "\n  ],\n");

        sb.append("  \"problems\": [");
        List<Analyzer.MsgStat> ps = a.topProblems(top);
        for (int i = 0; i < ps.size(); i++) {
            Analyzer.MsgStat s = ps.get(i);
            sb.append(i == 0 ? "\n" : ",\n").append("    {\"level\": ").append(quote(s.level.name()))
                    .append(", \"count\": ").append(s.count)
                    .append(", \"pattern\": ").append(quote(s.pattern))
                    .append(", \"logger\": ").append(quote(s.logger))
                    .append(", \"first\": ").append(ts(s.first))
                    .append(", \"last\": ").append(ts(s.last)).append("}");
        }
        sb.append(ps.isEmpty() ? "],\n" : "\n  ],\n");

        j.counters("loggers", a.top(a.loggers, top));
        j.counters("threads", a.top(a.threads, top));

        sb.append("  \"insights\": [");
        for (int i = 0; i < a.insights.size(); i++) {
            sb.append(i == 0 ? "\n    " : ",\n    ").append(quote(a.insights.get(i).text));
        }
        sb.append(a.insights.isEmpty() ? "]\n" : "\n  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private void counters(String name, List<Analyzer.Counter> list) {
        sb.append("  ").append(quote(name)).append(": [");
        for (int i = 0; i < list.size(); i++) {
            Analyzer.Counter c = list.get(i);
            sb.append(i == 0 ? "\n" : ",\n").append("    {\"name\": ").append(quote(c.name))
                    .append(", \"count\": ").append(c.count)
                    .append(", \"warnings\": ").append(c.warns)
                    .append(", \"errors\": ").append(c.errors).append("}");
        }
        sb.append(list.isEmpty() ? "],\n" : "\n  ],\n");
    }

    private void field(String key, String value) {
        sb.append("  ").append(quote(key)).append(": ").append(quote(value)).append(",\n");
    }

    private void field(String key, long value) {
        sb.append("  ").append(quote(key)).append(": ").append(value).append(",\n");
    }

    private static String ts(long ts) {
        return ts == LogEntry.NO_TS ? "null" : quote(Style.dateTime(ts).toString());
    }

    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder q = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': q.append("\\\""); break;
                case '\\': q.append("\\\\"); break;
                case '\n': q.append("\\n"); break;
                case '\r': q.append("\\r"); break;
                case '\t': q.append("\\t"); break;
                default:
                    if (ch < 0x20) q.append(String.format("\\u%04x", (int) ch));
                    else q.append(ch);
            }
        }
        return q.append('"').toString();
    }
}
