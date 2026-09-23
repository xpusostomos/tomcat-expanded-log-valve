# xpusostomos-log-valve

An Apache Tomcat 9 **access-log `Valve`** that extends the standard
`AccessLogValve` with:

* **Start-of-request lines** — up to three extra patterns (`patternBeg1`…`patternBeg3`)
  rendered when each request arrives.
* **Extra end-of-request lines** — up to three extra patterns
  (`patternEnd1`…`patternEnd3`) rendered when each request completes.
* **Three additional pattern codes**, usable in *any* of the patterns:
  * **`%P`** — the request parameters (form fields), rendered as
    `name=value&name=value`, URL-decoded.
  * **`%J`** — the request body (e.g. a JSON REST payload), captured without consuming
    it, for the content types configured via `bodyContentTypes`.
  * **`%N`** — a per-request sequence number, so start and end lines of one request
    can be correlated in multi-threaded or asynchronous environments.
* **Flood controls** — every extra line is opt-in; request bodies are truncated at
  `maxBodyLogSize`; all values are escaped so a request cannot forge or split log lines.

Everything else — all standard `%` codes, the `common`/`combined` aliases, file
management, daily rotation, buffering, conditional logging, TLS pattern elements — is
inherited unchanged from `AccessLogValve`. The valve is a single jar; drop it into
Tomcat's `lib` directory and configure it in `server.xml`.

## Requirements

* Tomcat **9.x** (uses `javax.servlet`; not compatible with Tomcat 10's `jakarta.servlet`)
* A Java 17+ JVM (the jar is compiled for Java 17, which Tomcat 9 supports)
* Developed and tested against Tomcat 9.0.121 (installed) / 9.0.122 (compile target)

## Building

```bash
cd expanded-log-valve
gradle test   # 18 integration tests against an embedded Tomcat 9
gradle jar    # produces build/libs/xpusostomos-log-valve.jar
```

## Installing

The jar must live on Tomcat's *common* class loader — its `lib` directory, **not** a
webapp's `WEB-INF/lib`. On a Debian-style installation (like the standard
`/usr/share/tomcat9` layout):

```bash
sudo cp build/libs/xpusostomos-log-valve.jar /usr/share/tomcat9/lib/
sudo systemctl restart tomcat9
```

Then add the valve to `server.xml` (`/etc/tomcat9/server.xml` on that layout), inside
`<Engine>`, `<Host>` or `<Context>`:

```xml
<Valve className="xpusostomos.tomcat.valves.ExpandedAccessLogValve"
       directory="/var/log/tomcat9" prefix="xpuso_" suffix=".log"
       pattern="%h %t &quot;%r&quot; %s %b"
       patternBeg1="START %t &quot;%r&quot;"
       patternEnd1="formParameters=&quot;%P&quot;"
       patternEnd2="body=&quot;%J&quot;"/>
```

Because everything is pattern-driven, placement of the new data is entirely up to you:

* `patternEnd1="formParameters=&quot;%P&quot; body=&quot;%J&quot;"` — both on one line,
* `patternEnd1="%P"` + `patternEnd2="%J"` — separate lines (the example above),
* `%P` / `%J` directly in the main `pattern` — appended to the end-of-request line.

### Sample output

From the configuration above:

```
START [22/Sep/2026:21:15:33 +0700] "POST /form HTTP/1.1"
0:0:0:0:0:0:0:1 [22/Sep/2026:21:15:33 +0700] "POST /form HTTP/1.1" 200 7
formParameters="a=hello&b=world x"
body="-"
START [22/Sep/2026:21:15:33 +0700] "POST /json HTTP/1.1"
0:0:0:0:0:0:0:1 [22/Sep/2026:21:15:33 +0700] "POST /json HTTP/1.1" 200 9
formParameters="-"
body="{\"k\":\"v\"}"
```

## Configuration attributes

### New attributes

| Attribute          | Default                    | Meaning |
|--------------------|----------------------------|---------|
| `patternBeg1` … `patternBeg3` | *(unset)*       | Patterns rendered at the **start** of each request, in order. Unset means no line. |
| `patternEnd1` … `patternEnd3` | *(unset)*       | Patterns rendered when each request **completes**, in order, after the main `pattern` line. Unset means no line. |
| `patternBegVerbs1` … `patternBegVerbs3` | *(unset)* | Comma-separated HTTP methods (e.g. `POST, PUT`) restricting the matching `patternBeg` slot. Unset means the pattern applies to every request; set means it renders only for the listed methods (case-insensitive, whitespace tolerated). |
| `patternEndVerbs1` … `patternEndVerbs3` | *(unset)* | The same, for `patternEnd1` … `patternEnd3`. |
| `bodyContentTypes` | `application/json, *+json` | Comma-separated content types whose request bodies are captured for `%J`. Exact types (`application/json`), `*` (any body) and the suffix wildcard `*+json` (any `…+json` type, e.g. `application/vnd.api+json`) are supported. |
| `maxBodyLogSize`   | `4096`                     | Maximum number of body bytes captured per request; longer bodies are truncated with a `...[truncated]` marker. |

`patternBeg`/`patternEnd` accept the same values as `pattern`, including the
`common` and `combined` aliases.

### New pattern codes

| Code | Renders |
|------|---------|
| `%P` | Request parameters as `name=value&name=value` (multi-value parameters repeat the name: `a=1&a=2`). URL-decoded. Includes query-string parameters as well as form-body parameters. `-` when the request has no parameters. |
| `%J` | The captured request body (JSON etc.), escaped. `-` when there is no body to capture, `(not read)` when a body was present but the application never read it, `...[truncated]` appended beyond `maxBodyLogSize`. |
| `%N` | The valve's per-request sequence number (1, 2, 3, …), assigned when a request first passes through the valve and available until it completes (asynchronous completion included) — correlates a request's start and end lines in multi-threaded environments. `-` when unavailable (e.g. dispatches the valve did not see). Resets on Tomcat restart; pair with `%t` (and the container id on container platforms) to disambiguate restarts and replicas. |

All three codes work in the main `pattern` and in any `patternBeg`/`patternEnd`.

### Restricting patterns by HTTP method

Each auxiliary pattern has an optional companion attribute that gates it by request
method: `patternBegVerbs1` gates `patternBeg1`, `patternEndVerbs2` gates
`patternEnd2`, and so on:

```xml
<Valve className="xpusostomos.tomcat.valves.ExpandedAccessLogValve"
       directory="/var/log/tomcat9" prefix="xpuso_" suffix=".log"
       pattern=""
       patternBeg1="START %N %t &quot;%r&quot;"
       patternEnd1="END   %N %t &quot;%r&quot; %s %b %D"
       patternEnd2="END   %N parameters=&quot;%P&quot;"
       patternEnd3="END   %N body=%J"
       patternEndVerbs2="POST, PUT, PATCH"
       patternEndVerbs3="POST, PUT, PATCH"/>
```

With this configuration the start and end lines are logged for every request, but
the parameter and body lines only appear for POST, PUT and PATCH — keeping the log
quiet for GET-heavy traffic. The comparison is case-insensitive (`get` matches
`GET`) and surrounding whitespace is ignored; an unset (or empty) verb list means the
pattern applies to every request. A gated-out slot renders nothing and runs none of
its pattern elements — e.g. a gated-out start slot containing `%P` does not force
parameter parsing.

### Inherited attributes

Everything from the standard
[Access Log Valve](https://tomcat.apache.org/tomcat-9.0-doc/config/valve.html#Access_Log_Valve)
applies: `directory`, `prefix`, `suffix`, `rotatable`, `renameOnRotate`,
`fileDateFormat`, `buffered`, `encoding`, `conditionUnless`/`conditionIf`
(conditional logging), `enabled`, `ipv6Canonical`, `maxLogMessageBufferSize`, and all
standard `%` codes (`%h %t "%r" %s %b %D …`, `%{…}i/o/c/r/s/a/p/t`).

Like the stock valve, the main `pattern` must be set explicitly to get a main
end-of-request line — unset means no main line (`pattern=""` is equivalent). Two of
the inherited defaults matter a lot when logging to a character device instead of a
file — see [Logging to stdout](#logging-to-stdout-journal-catalinaout-console) below.

### A full example: all six auxiliary slots

```xml
<Valve className="xpusostomos.tomcat.valves.ExpandedAccessLogValve"
       directory="/var/log/tomcat9" prefix="xpuso_" suffix=".log"
       pattern=""
       patternBeg1="START %N %t &quot;%r&quot; from %h"
       patternBeg2="START %N agent=%{User-Agent}i"
       patternBeg3="START %N params=%P"
       patternEnd1="END   %N %t &quot;%r&quot; %s %b %D"
       patternEnd2="END   %N parameters=&quot;%P&quot;"
       patternEnd3="END   %N body=%J"/>
```

Sample output for a plain `GET /healthz` and a JSON `POST /api/orders`:

```
START 1 [23/Sep/2026:10:15:01 +0700] "GET /healthz HTTP/1.1" from 192.168.1.5
START 1 agent=curl/8.5.0
START 1 params=-
END   1 [23/Sep/2026:10:15:01 +0700] "GET /healthz HTTP/1.1" 200 22 943
END   1 parameters="-"
END   1 body=-
START 2 [23/Sep/2026:10:15:05 +0700] "POST /api/orders HTTP/1.1" from 192.168.1.5
START 2 agent=curl/8.5.0
START 2 params=-
END   2 [23/Sep/2026:10:15:05 +0700] "POST /api/orders HTTP/1.1" 200 9 3121
END   2 parameters="-"
END   2 body="{\"item\":\"widget\",\"qty\":2}"
```

Notes on this example:

* `%N` stamps every line with the request's sequence number, so a request's six lines
  can be reassembled even when concurrent requests interleave them (see
  [Behaviour details](#behaviour-details)).
* `pattern=""` suppresses the main line entirely — the six auxiliary slots carry
  everything. Delete that attribute (and set e.g. `pattern="common"`) if you also want
  the standard line.
* `%P` in `patternBeg3` shows query-string parameters immediately (form-body
  parameters are also available there, at the cost of forcing parameter parsing at
  request start — see Behaviour details).
* `%s`, `%b`, `%D` in `patternEnd1` are the real response values; in the `patternBeg`
  lines they would always show pre-execution values (`200`, `-`, `0`).

## Logging to stdout (journal, catalina.out, console)

To send the log to Tomcat's stdout — useful when running under systemd
(`journalctl -u tomcat9`) or in a container where stdout is collected:

```xml
<Valve className="xpusostomos.tomcat.valves.ExpandedAccessLogValve"
       directory="/dev/stdout" prefix="" suffix=""
       rotatable="false" buffered="false"
       pattern=""
       patternBeg1="START %N %h %l %u %t &quot;%r&quot; %s %b %T"
       patternEnd1="END   %N %h %l %u %t &quot;%r&quot; %s %b %T"
       patternEnd2="END   %N parameters=&quot;%P&quot;"
       patternEnd3="END   %N body=%J"/>
```

Two inherited defaults **must** be overridden for this to work:

* **`rotatable="false"`** — with the default `true` the valve inserts a date stamp
  into the file name, producing `/dev/stdout/2026-09-23`. That is a file *inside* a
  pipe, the open fails, and from then on the valve silently drops every message (its
  error — `accessLogValve.openFail` — goes to Tomcat's own log, not the access log).
  This is the classic "valve configured, nothing comes out" failure.
* **`buffered="false"`** — with the default `true`, lines are held in a 128 KB buffer
  that is only flushed on day rotation or shutdown, so with sparse traffic you see
  nothing for a long time.

The `directory` value follows Tomcat's stdout wherever that goes: the terminal with
`catalina.sh run`, `catalina.out` with `catalina.sh start`, or the systemd journal
under a service unit.

## Behaviour details

* **Exactly one end line per client request.** The container calls the valve's logging
  hook once per request, when it *completes* — including requests that went
  asynchronous or ended in an error. Start lines are emitted only for original request
  dispatches, so async, error and forward dispatches do not produce duplicate `START`
  lines. (If the valve sits on a `<Context>`, forwarded requests within that context do
  produce an extra start line, matching the dispatch.)
* **Response-dependent elements on start lines** (`%s`, `%b`, `%D`, …) show their
  pre-execution values, e.g. status `200`, bytes `-`, elapsed `0`. That is why start
  lines are usually paired with a prefix, as in the example.
* **Body capture is non-invasive.** A small request wrapper tees the body *as the
  application reads it* (same approach as Spring's `ContentCachingRequestWrapper`), so
  streaming applications are unaffected. The consequence: a body the application never
  reads is logged as `(not read)`, not with its content.
* **Form parameters** come from `request.getParameterMap()`, which includes
  query-string parameters as well as form-body parameters; Tomcat parses and caches
  them, so servlets using `getParameter()` are unaffected. For `multipart/form-data`,
  non-file fields appear in `%P` (file uploads contribute field names only, never file
  contents). Using `%P` in a *beginning* pattern forces parameter parsing at the start
  of the request — safe for normal applications, but an application that reads the raw
  input stream of a form post itself will see an empty stream.
* **Escaping.** Values are escaped so a request cannot forge log entries: `\`,
  `"` , CR, LF and TAB become `\`, `\"`, `\r`, `\n`, `\t`; other control characters
  become `\xNN`. One request always produces one physical line per pattern.
* **Conditional logging** (`conditionUnless`/`conditionIf`, inherited) applies to the
  start and auxiliary end lines exactly as it does to the main line.
* **Correlating lines under concurrency.** Each line is written atomically, but a
  request's lines are separate writes, so under load lines from different requests can
  interleave. Include `%N` (the per-request sequence number) in every pattern you want
  to group by, and a request's lines can be reassembled with a simple filter — this
  works for asynchronous requests too, where the thread that completes the request is
  often not the thread that started it. The stock thread-name code `%I` is a useful
  hint, but thread names are reused from the pool and may differ between the start
  and end of an async request, so prefer `%N` for correlation.
* **Method gating** (`patternBegVerbs*` / `patternEndVerbs*`) is evaluated per line at
  render time: a gated-out slot produces no line at all — not even a dash placeholder.

> **Warning:** `%P` logs credentials submitted in login forms, and `%J` may log
> authentication payloads. Apply conditional logging or filter the logs downstream;
> do not place access logs with parameters in publicly readable locations.

## Testing

The test suite (23 integration tests) runs the valve inside an embedded Tomcat 9 and
covers: default (stock-like) behaviour, start/end line ordering, the three-line
pattern slots, form parameters (including multi-value, main-pattern placement and
start-pattern placement), JSON bodies (captured, unread, wrong content type, vendor
`+json` types, custom `bodyContentTypes`, truncation, escaping), multipart form
fields, async requests (logged once, body captured across threads, start/end
correlated by `%N`), `%N` sequence correlation (sequential and concurrent requests),
HTTP-method gating of individual slots (start and end, case-insensitivity) and
blank-line suppression.

```bash
gradle test
```

A smoke test against the real installed Tomcat was also performed with a throwaway
`CATALINA_BASE` (`/tmp/xpuso-valve-smoke`), confirming the drop-in jar + `server.xml`
configuration documented above.

## Project layout

```
build.gradle, settings.gradle   Gradle build (Java 17, Tomcat 9.0.122 compile deps)
src/main/java/xpusostomos/tomcat/valves/
  ExpandedAccessLogValve.java   the valve
  BodyCachingRequestWrapper.java request-body tee wrapper
src/test/java/xpusostomos/tomcat/valves/
  ExpandedAccessLogValveTest.java  integration tests (embedded Tomcat)
  TestServlet.java                 test endpoints
LICENSE, NOTICE                 Apache License 2.0
```

## License

Apache License, Version 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE). The valve
links against and extends Apache Tomcat's `AccessLogValve` (also Apache-2.0) and uses
only its public and subclass extension points — no Tomcat source is copied.