package dev.loglens;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** ANSI styling, width-aware text helpers and number formatting. */
final class Style {
    static boolean color = false;
    static boolean truecolor = true;

    static final int MUTED = 0x8B98AD, FAINT = 0x566175, BORDER = 0x4A566B;
    static final int ACCENT = 0x67E8F9, VIOLET = 0xA78BFA, PINK = 0xF472B6;
    static final int GREEN = 0x4ADE80, AMBER = 0xFBBF24, RED = 0xF87171, MAGENTA = 0xE879F9;
    static final int BLUE = 0x60A5FA, TEAL = 0x2DD4BF, SLATE = 0x7C8AA0;

    private static final String ESC = "\u001B[";
    static final String RESET = ESC + "0m";
    private static final String BLOCKS = " ▏▎▍▌▋▊▉█";
    private static final String SPARKS = " ▁▂▃▄▅▆▇█";

    private Style() {
    }

    private static String code(int rgb, boolean background) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        String kind = background ? "48" : "38";
        if (truecolor) return ESC + kind + ";2;" + r + ";" + g + ";" + b + "m";
        int idx = 16 + 36 * Math.round(r / 255f * 5) + 6 * Math.round(g / 255f * 5) + Math.round(b / 255f * 5);
        return ESC + kind + ";5;" + idx + "m";
    }

    /** Coloured text. */
    static String c(int rgb, String s) {
        return !color || s.isEmpty() ? s : code(rgb, false) + s + RESET;
    }

    /** Bold coloured text. */
    static String b(int rgb, String s) {
        return !color || s.isEmpty() ? s : ESC + "1m" + code(rgb, false) + s + RESET;
    }

    static String bold(String s) {
        return !color || s.isEmpty() ? s : ESC + "1m" + s + RESET;
    }

    /** Dark text on a coloured background. */
    static String badge(int rgb, String s) {
        return !color ? "[" + s.trim() + "]" : ESC + "1;30m" + code(rgb, true) + s + RESET;
    }

    static String highlight(String s) {
        return !color ? s : ESC + "30m" + code(AMBER, true) + s + RESET;
    }

    static int lerp(int from, int to, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) Math.round(((from >> 16) & 0xFF) + t * (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)));
        int g = (int) Math.round(((from >> 8) & 0xFF) + t * (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)));
        int b = (int) Math.round((from & 0xFF) + t * ((to & 0xFF) - (from & 0xFF)));
        return (r << 16) | (g << 8) | b;
    }

    /** Horizontal three-stop gradient across a line of text. */
    static String gradient(String s) {
        if (!color) return s;
        StringBuilder sb = new StringBuilder();
        int n = Math.max(1, s.length() - 1);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == ' ') {
                sb.append(' ');
                continue;
            }
            double t = (double) i / n;
            int rgb = t < 0.5 ? lerp(ACCENT, VIOLET, t * 2) : lerp(VIOLET, PINK, (t - 0.5) * 2);
            sb.append(ESC).append("1m").append(code(rgb, false)).append(ch);
        }
        return sb.append(RESET).toString();
    }

    // ---------------------------------------------------------------- width-aware text

    /** Visible length, ignoring ANSI escape sequences. */
    static int vlen(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\u001B') {
                while (i < s.length() && s.charAt(i) != 'm') i++;
            } else {
                n++;
            }
        }
        return n;
    }

    static String repeat(String s, int n) {
        return n <= 0 ? "" : s.repeat(n);
    }

    static String pad(String s, int width) {
        return s + repeat(" ", width - vlen(s));
    }

    static String lpad(String s, int width) {
        return repeat(" ", width - vlen(s)) + s;
    }

    /** Truncates plain text with an ellipsis. */
    static String trunc(String s, int width) {
        if (width <= 0) return "";
        return s.length() <= width ? s : s.substring(0, Math.max(0, width - 1)) + "…";
    }

    /** Truncates (ANSI-aware) and pads to exactly {@code width} visible columns. */
    static String fit(String s, int width) {
        int len = vlen(s);
        if (len <= width) return s + repeat(" ", width - len);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < s.length() && n < width - 1; i++) {
            char ch = s.charAt(i);
            if (ch == '\u001B') {
                int end = s.indexOf('m', i);
                if (end < 0) break;
                sb.append(s, i, end + 1);
                i = end;
            } else {
                sb.append(ch);
                n++;
            }
        }
        sb.append('…');
        if (color) sb.append(RESET);
        return sb.toString();
    }

    /** Shortens package segments: com.example.shop.OrderService -> c.e.s.OrderService. */
    static String abbreviate(String name, int max) {
        if (name == null) return "";
        if (name.length() <= max) return name;
        String[] parts = name.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].length() > 1) parts[i] = parts[i].substring(0, 1);
            String joined = String.join(".", parts);
            if (joined.length() <= max) return joined;
        }
        String joined = String.join(".", parts);
        return joined.length() <= max ? joined : "…" + joined.substring(joined.length() - Math.max(1, max - 1));
    }

    // ---------------------------------------------------------------- charts

    /** Horizontal bar with sub-character precision on a faint dotted track. */
    static String bar(double fraction, int width, int rgb) {
        if (width <= 0) return "";
        fraction = Math.max(0, Math.min(1, fraction));
        int eighths = (int) Math.round(fraction * width * 8);
        if (fraction > 0 && eighths == 0) eighths = 1;
        int full = eighths / 8, rem = eighths % 8;
        StringBuilder filled = new StringBuilder(repeat("█", full));
        if (rem > 0) filled.append(BLOCKS.charAt(rem));
        return c(rgb, filled.toString()) + c(FAINT, repeat("·", width - filled.length()));
    }

    static char spark(long value, long max) {
        if (value <= 0 || max <= 0) return ' ';
        int idx = (int) Math.max(1, Math.round(value * 8.0 / max));
        return SPARKS.charAt(Math.min(8, idx));
    }

    static char sparkEighth(int eighths) {
        return SPARKS.charAt(Math.max(0, Math.min(8, eighths)));
    }

    // ---------------------------------------------------------------- formatting

    static String num(long n) {
        return String.format("%,d", n);
    }

    static String compact(long n) {
        if (n < 1000) return Long.toString(n);
        if (n < 10_000) return String.format("%.1fk", n / 1000.0);
        if (n < 1_000_000) return (n / 1000) + "k";
        return String.format("%.1fM", n / 1_000_000.0);
    }

    static String pct(long part, long total) {
        if (total <= 0) return "0%";
        double p = part * 100.0 / total;
        if (p > 0 && p < 0.1) return "<0.1%";
        return String.format("%.1f%%", p);
    }

    static String bytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format("%.1f MB", n / (1024.0 * 1024));
        return String.format("%.2f GB", n / (1024.0 * 1024 * 1024));
    }

    static String duration(long ms) {
        if (ms < 1000) return ms + " ms";
        long s = ms / 1000;
        if (s < 60) return String.format("%.1f s", ms / 1000.0);
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (d > 0) return d + "d " + h + "h " + m + "m";
        if (h > 0) return h + "h " + m + "m " + sec + "s";
        return m + "m " + sec + "s";
    }

    static LocalDateTime dateTime(long ts) {
        return LocalDateTime.ofEpochSecond(Math.floorDiv(ts, 1000L), (int) Math.floorMod(ts, 1000L) * 1_000_000, ZoneOffset.UTC);
    }

    static String time(long ts) {
        LocalDateTime t = dateTime(ts);
        return String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
    }

    static String timeMs(long ts) {
        return time(ts) + String.format(".%03d", Math.floorMod(ts, 1000L));
    }

    static String date(long ts) {
        return dateTime(ts).toLocalDate().toString();
    }

    static String dayTime(long ts) {
        LocalDateTime t = dateTime(ts);
        return String.format("%02d-%02d %02d:%02d", t.getMonthValue(), t.getDayOfMonth(), t.getHour(), t.getMinute());
    }
}
