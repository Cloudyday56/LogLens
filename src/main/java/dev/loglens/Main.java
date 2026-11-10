package dev.loglens;

import java.io.BufferedReader;
import java.io.Console;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.GZIPInputStream;

import static dev.loglens.Style.*;

public final class Main {
    static final String NAME = "LogLens";
    static final String VERSION = "1.0.0";

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    // options
    private final List<String> inputs = new ArrayList<>();
    private Level minLevel;
    private Level failOn;
    private Pattern grep;
    private String grepText, loggerFilter, threadFilter, fromText, toText;
    private int top = 6, width = -1, head, tail;
    private boolean list, json, demo;
    private int demoEntries = 9000;
    private String generateTo;
    private String colorMode = "auto";

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        int code;
        color = isTerminal() && System.getenv("NO_COLOR") == null;
        try {
            code = new Main().run(args, out);
        } catch (UsageException e) {
            err.println(c(RED, "error: ") + e.getMessage());
            err.println("Run with --help to see the available options.");
            code = 64;
        } catch (IOException e) {
            err.println(c(RED, "error: ") + e.getMessage());
            code = 1;
        }
        out.flush();
        System.exit(code);
    }

    private int run(String[] args, PrintStream out) throws IOException {
        parseArgs(args);
        boolean tty = isTerminal();
        color = colorMode.equals("always") || (colorMode.equals("auto") && tty && System.getenv("NO_COLOR") == null);
        String colorterm = System.getenv("COLORTERM");
        truecolor = colorterm != null && (colorterm.contains("truecolor") || colorterm.contains("24bit"));
        String forced = System.getenv("LOGLENS_COLORS"); // "256" or "truecolor" overrides detection
        if (forced != null) truecolor = !forced.equals("256");
        if (json) color = false;

        if (inputs.isEmpty() && !demo && generateTo == null) {
            if (args.length == 0) {
                help(out);
                return 0;
            }
            throw new UsageException("no log file given");
        }
        if (generateTo != null) {
            Files.writeString(Paths.get(generateTo), DemoLog.generate(demoEntries));
            out.println("Wrote sample log to " + generateTo);
            return 0;
        }

        Analyzer analyzer = new Analyzer();
        Lister lister = list ? new Lister(out, grep, head, tail) : null;
        long[] totals = new long[2]; // entries seen, entries at/above --fail-on
        long[] bounds = {Long.MIN_VALUE, Long.MAX_VALUE};
        boolean[] boundsResolved = {fromText == null && toText == null};

        java.util.function.Consumer<LogEntry> sink = e -> {
            totals[0]++;
            if (!boundsResolved[0] && e.hasTs()) {
                LocalDate day = dateTime(e.ts).toLocalDate();
                if (fromText != null) bounds[0] = parseBound(fromText, day, false);
                if (toText != null) bounds[1] = parseBound(toText, day, true);
                boundsResolved[0] = true;
            }
            if (!matches(e, bounds)) return;
            if (failOn != null && e.level.ordinal() >= failOn.ordinal()) totals[1]++;
            if (lister != null) lister.accept(e);
            else analyzer.accept(e);
        };

        long started = System.nanoTime();
        long lines = 0, size = 0;
        String source;
        if (demo) {
            String text = DemoLog.generate(demoEntries);
            LogParser parser = new LogParser(sink);
            parser.parse(new BufferedReader(new StringReader(text)));
            lines = parser.lines;
            size = text.length();
            source = "built-in demo log";
        } else {
            for (String input : inputs) {
                LogParser parser = new LogParser(sink);
                try (BufferedReader reader = open(input)) {
                    parser.parse(reader);
                }
                lines += parser.lines;
                if (!input.equals("-")) size += Files.size(Paths.get(input));
                else size = -1;
            }
            source = inputs.size() == 1 ? (inputs.get(0).equals("-") ? "stdin" : inputs.get(0)) : inputs.size() + " files: " + String.join(", ", inputs);
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        if (lister != null) {
            lister.finish();
        } else {
            analyzer.finish();
            if (json) {
                out.print(JsonExport.render(analyzer, source, lines, totals[0], top));
            } else {
                Dashboard d = new Dashboard(analyzer, resolveWidth(tty), top);
                d.source = source;
                d.sizeBytes = size;
                d.totalLines = lines;
                d.totalEntries = totals[0];
                d.elapsedMs = elapsed;
                d.filterSummary = filterSummary();
                out.print(d.render());
            }
        }
        return failOn != null && totals[1] > 0 ? 2 : 0;
    }

    // ---------------------------------------------------------------- filtering

    private boolean matches(LogEntry e, long[] bounds) {
        if (minLevel != null && e.level.ordinal() < minLevel.ordinal()) return false;
        if (loggerFilter != null && (e.logger == null || !e.logger.toLowerCase().contains(loggerFilter))) return false;
        if (threadFilter != null && (e.thread == null || !e.thread.toLowerCase().contains(threadFilter))) return false;
        if (fromText != null || toText != null) {
            if (!e.hasTs() || e.ts < bounds[0] || e.ts > bounds[1]) return false;
        }
        if (grep != null && !grep.matcher(e.message).find()) {
            boolean found = false;
            if (e.extra != null) {
                for (String line : e.extra) {
                    if (grep.matcher(line).find()) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /** Accepts "2026-11-09 10:15[:30]", "2026-11-09T10:15", "2026-11-09" or a bare "10:15[:30]". */
    private static long parseBound(String text, LocalDate logDay, boolean end) {
        String t = text.trim().replace('T', ' ');
        try {
            LocalDateTime dt;
            if (t.matches("\\d{4}-\\d{2}-\\d{2}")) {
                dt = end ? LocalDate.parse(t).atTime(LocalTime.MAX) : LocalDate.parse(t).atStartOfDay();
            } else if (t.matches("\\d{1,2}:\\d{2}(:\\d{2})?")) {
                dt = logDay.atTime(time(t, end));
            } else {
                String[] parts = t.split("\\s+");
                dt = LocalDate.parse(parts[0]).atTime(time(parts[1], end));
            }
            return dt.toEpochSecond(ZoneOffset.UTC) * 1000L + dt.getNano() / 1_000_000;
        } catch (RuntimeException e) {
            throw new UsageException("cannot understand time '" + text + "' (use e.g. \"2026-11-09 10:15\" or \"10:15:30\")");
        }
    }

    private static LocalTime time(String t, boolean end) {
        String[] p = t.split(":");
        int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
        if (p.length > 2) return LocalTime.of(h, m, (int) Double.parseDouble(p[2]), end ? 999_000_000 : 0);
        return end ? LocalTime.of(h, m, 59, 999_000_000) : LocalTime.of(h, m);
    }

    private String filterSummary() {
        List<String> parts = new ArrayList<>();
        if (minLevel != null) parts.add("level ≥ " + minLevel);
        if (grepText != null) parts.add("/" + grepText + "/");
        if (loggerFilter != null) parts.add("logger ~ " + loggerFilter);
        if (threadFilter != null) parts.add("thread ~ " + threadFilter);
        if (fromText != null) parts.add("from " + fromText);
        if (toText != null) parts.add("to " + toText);
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    // ---------------------------------------------------------------- I/O

    private static BufferedReader open(String input) throws IOException {
        InputStream in;
        if (input.equals("-")) {
            in = System.in;
        } else {
            Path path = Paths.get(input);
            if (!Files.exists(path)) throw new IOException("file not found: " + input);
            if (Files.isDirectory(path)) throw new IOException(input + " is a directory");
            if (!Files.isReadable(path)) throw new IOException("cannot read " + input);
            in = Files.newInputStream(path);
            if (input.endsWith(".gz")) in = new GZIPInputStream(in, 1 << 16);
        }
        // Tolerate invalid bytes instead of failing half-way through a large file.
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)), 1 << 16);
    }

    private static boolean isTerminal() {
        Console console = System.console();
        if (console == null) return false;
        try {
            return (Boolean) Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    private int resolveWidth(boolean tty) {
        int w = width;
        if (w <= 0) {
            try {
                String cols = System.getenv("COLUMNS");
                if (cols != null) w = Integer.parseInt(cols.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        if (w <= 0 && tty) w = sttyWidth();
        if (w <= 0) w = 118;
        return Math.max(72, Math.min(170, w));
    }

    private static int sttyWidth() {
        try {
            Process p = new ProcessBuilder("sh", "-c", "stty size < /dev/tty").redirectErrorStream(true).start();
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            String[] parts = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim().split("\\s+");
            return parts.length == 2 ? Integer.parseInt(parts[1]) : -1;
        } catch (IOException | RuntimeException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    // ---------------------------------------------------------------- arguments

    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String inline = null;
            if (arg.startsWith("--") && arg.contains("=")) {
                inline = arg.substring(arg.indexOf('=') + 1);
                arg = arg.substring(0, arg.indexOf('='));
            }
            switch (arg) {
                case "-h": case "--help":
                    color = System.getenv("NO_COLOR") == null && isTerminal();
                    PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
                    help(out);
                    System.exit(0);
                    break;
                case "-V": case "--version":
                    System.out.println(NAME + " " + VERSION);
                    System.exit(0);
                    break;
                case "-l": case "--level": minLevel = level(arg, inline != null ? inline : value(args, ++i, arg)); break;
                case "--fail-on": failOn = level(arg, inline != null ? inline : value(args, ++i, arg)); break;
                case "-g": case "--grep":
                    grepText = inline != null ? inline : value(args, ++i, arg);
                    try {
                        grep = Pattern.compile(grepText, Pattern.CASE_INSENSITIVE);
                    } catch (PatternSyntaxException e) {
                        throw new UsageException("invalid regular expression for " + arg + ": " + e.getDescription());
                    }
                    break;
                case "--logger": loggerFilter = (inline != null ? inline : value(args, ++i, arg)).toLowerCase(); break;
                case "--thread": threadFilter = (inline != null ? inline : value(args, ++i, arg)).toLowerCase(); break;
                case "--from": fromText = inline != null ? inline : value(args, ++i, arg); break;
                case "--to": toText = inline != null ? inline : value(args, ++i, arg); break;
                case "-n": case "--top": top = positive(arg, inline != null ? inline : value(args, ++i, arg)); break;
                case "-w": case "--width": width = positive(arg, inline != null ? inline : value(args, ++i, arg)); break;
                case "--list": list = true; break;
                case "--head": list = true; head = positive(arg, inline != null ? inline : value(args, ++i, arg)); break;
                case "--tail": list = true; tail = positive(arg, inline != null ? inline : value(args, ++i, arg)); break;
                case "--json": json = true; break;
                case "--demo": demo = true; break;
                case "--generate": generateTo = inline != null ? inline : value(args, ++i, arg); break;
                case "--no-color": colorMode = "never"; break;
                case "--color":
                    colorMode = inline != null ? inline : value(args, ++i, arg);
                    if (!colorMode.matches("auto|always|never")) throw new UsageException("--color must be auto, always or never");
                    break;
                default:
                    if (arg.startsWith("-") && arg.length() > 1) throw new UsageException("unknown option " + arg);
                    inputs.add(args[i]);
            }
        }
        if (fromText != null) parseBound(fromText, LocalDate.now(), false); // validate early
        if (toText != null) parseBound(toText, LocalDate.now(), true);
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) throw new UsageException(option + " needs a value");
        return args[i];
    }

    private static Level level(String option, String text) {
        Level lv = Level.parse(text);
        if (lv == Level.UNKNOWN) throw new UsageException(option + " must be one of TRACE, DEBUG, INFO, WARN, ERROR, FATAL");
        return lv;
    }

    private static int positive(String option, String text) {
        try {
            int n = Integer.parseInt(text);
            if (n > 0) return n;
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new UsageException(option + " needs a positive number, got '" + text + "'");
    }

    private static void help(PrintStream out) {
        String[] art = {"╻  ┏━┓┏━╸╻  ┏━╸┏┓╻┏━┓", "┃  ┃ ┃┃╺┓┃  ┣╸ ┃┗┫┗━┓", "┗━╸┗━┛┗━┛┗━╸┗━╸╹ ╹┗━┛"};
        out.println();
        for (String a : art) out.println("  " + gradient(a));
        out.println();
        out.println("  " + bold("Java log analysis for the terminal") + c(MUTED, "  v" + VERSION));
        out.println("  " + c(MUTED, "Understands Spring Boot, Logback, Log4j, Tomcat and java.util.logging output."));
        out.println();
        out.println(b(ACCENT, "  USAGE"));
        out.println("    java -jar loglens.jar [options] <file>...     " + c(MUTED, "(.gz files and '-' for stdin work too)"));
        out.println();
        out.println(b(ACCENT, "  FILTERS"));
        opt(out, "-l, --level LEVEL", "only entries at or above LEVEL (TRACE, DEBUG, INFO, WARN, ERROR, FATAL)");
        opt(out, "-g, --grep REGEX", "only entries whose message or stack trace matches (case-insensitive)");
        opt(out, "    --logger TEXT", "only entries whose logger name contains TEXT");
        opt(out, "    --thread TEXT", "only entries whose thread name contains TEXT");
        opt(out, "    --from TIME", "only entries at or after TIME  (\"2026-11-09 10:15\" or just \"10:15\")");
        opt(out, "    --to TIME", "only entries at or before TIME");
        out.println();
        out.println(b(ACCENT, "  OUTPUT"));
        opt(out, "    (default)", "dashboard: levels, timeline, exceptions, recurring problems, insights");
        opt(out, "    --list", "print the matching entries instead of the dashboard");
        opt(out, "    --head N, --tail N", "like --list, limited to the first / last N entries");
        opt(out, "    --json", "print the report as JSON");
        opt(out, "-n, --top N", "rows per table (default 6)");
        opt(out, "-w, --width N", "dashboard width (default: terminal width)");
        opt(out, "    --color MODE", "auto, always or never (NO_COLOR is respected)");
        out.println();
        out.println(b(ACCENT, "  OTHER"));
        opt(out, "    --fail-on LEVEL", "exit with status 2 if any entry at or above LEVEL exists (for CI)");
        opt(out, "    --demo", "analyse a built-in sample log");
        opt(out, "    --generate FILE", "write the sample log to FILE");
        opt(out, "-h, --help", "show this help");
        opt(out, "-V, --version", "show the version");
        out.println();
        out.println(b(ACCENT, "  EXAMPLES"));
        out.println("    java -jar loglens.jar app.log");
        out.println("    java -jar loglens.jar app.log --level WARN --from 09:00 --to 09:30");
        out.println("    java -jar loglens.jar app.log --grep \"timed? ?out\" --tail 20");
        out.println("    kubectl logs my-pod | java -jar loglens.jar -");
        out.println();
    }

    private static void opt(PrintStream out, String flag, String text) {
        out.println("    " + c(GREEN, pad(flag, 22)) + text);
    }
}
