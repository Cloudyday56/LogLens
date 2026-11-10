# LogLens

Java log analysis for the terminal. Point it at a log file and get a dashboard:
level breakdown, activity timeline, top exceptions with root causes, recurring
problems, noisiest loggers and threads, and plain-language insights.

Pure Java 17+, zero dependencies, one small jar.

## Quick start

```bash
./build.sh
```

```bash
java -jar loglens.jar samples/shop.log
```

No log at hand? `java -jar loglens.jar --demo` analyses a built-in sample.

Maven works too: `mvn package` produces `target/loglens.jar`.

## What it understands

| Format | Example |
| --- | --- |
| Spring Boot | `2026-11-09 08:00:00.123  INFO 4821 --- [main] c.a.App : Started` |
| Logback | `14:02:11.120 [main] INFO  com.example.App - Starting` |
| Log4j 1/2 | `2026-03-01 10:00:00,123 INFO  [main] org.acme.Server:42 - Listening` |
| Tomcat | `10-Nov-2026 21:15:10.250 INFO [main] org.apache.catalina... ` |
| java.util.logging | `Nov 10, 2026 9:15:02 AM com.example.Billing charge` / `INFO: ...` |
| Plain | `[ERROR] Failed to execute goal` |

Stack traces are folded into the entry that produced them, and `Caused by:`
chains are followed to the root cause. `.gz` files and stdin (`-`) are supported,
and several files can be analysed together.

## Usage

```
java -jar loglens.jar [options] <file>...

Filters
  -l, --level LEVEL     only entries at or above LEVEL
  -g, --grep REGEX      only entries whose message or stack trace matches
      --logger TEXT     only entries whose logger name contains TEXT
      --thread TEXT     only entries whose thread name contains TEXT
      --from TIME       "2026-11-09 10:15" or just "10:15"
      --to TIME

Output
      (default)         dashboard
      --list            print the matching entries instead
      --head N, --tail N
      --json            machine-readable report
  -n, --top N           rows per table (default 6)
  -w, --width N         dashboard width (default: terminal width)
      --color MODE      auto, always, never (NO_COLOR is respected)

Other
      --fail-on LEVEL   exit status 2 if such entries exist (for CI)
      --demo            analyse a built-in sample log
      --generate FILE   write the sample log to FILE
```

## Examples

```bash
java -jar loglens.jar app.log --level WARN --from 09:00 --to 09:30
```

```bash
java -jar loglens.jar app.log --grep "timed? ?out" --tail 20
```

```bash
kubectl logs my-pod | java -jar loglens.jar -
```

```bash
java -jar loglens.jar app.log --json > report.json
```

Colours use 24-bit when the terminal advertises it (`COLORTERM=truecolor`) and
fall back to 256 colours otherwise; set `LOGLENS_COLORS=truecolor` or `256` to
force one. Piped output is plain text.
