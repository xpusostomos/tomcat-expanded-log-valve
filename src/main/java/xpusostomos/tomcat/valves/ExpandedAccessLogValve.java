/*
 * Copyright 2026 Chris Bitmead
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xpusostomos.tomcat.valves;

import java.io.CharArrayWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServletRequest;

import org.apache.catalina.Globals;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.AccessLogValve;
import org.apache.catalina.valves.Constants;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * An extension of the standard Tomcat {@link AccessLogValve} that can additionally:
 * <ul>
 * <li>log a line at the <b>start</b> of each request (up to three patterns:
 * {@code patternBeg1}, {@code patternBeg2}, {@code patternBeg3}),</li>
 * <li>log extra lines at the <b>end</b> of each request (up to three patterns:
 * {@code patternEnd1}, {@code patternEnd2}, {@code patternEnd3}),</li>
 * <li>render two additional pattern codes, usable in any of the patterns:
 * <ul>
 * <li><b>{@code %P}</b> - the request parameters (form fields), rendered as
 * {@code name=value&name=value} with values URL-decoded,</li>
 * <li><b>{@code %J}</b> - the request body (e.g. a JSON REST payload), captured without
 * consuming it, for the content types configured via {@code bodyContentTypes}.</li>
 * </ul>
 * </li>
 * </ul>
 * <p>
 * Everything else - all standard pattern codes and aliases ({@code common},
 * {@code combined}), file management, rotation, buffering, conditional logging - is
 * inherited from {@link AccessLogValve} and works unchanged. Patterns are compiled with
 * the inherited {@link org.apache.catalina.valves.AbstractAccessLogValve} machinery: the
 * extra codes are recognised here and everything else is delegated to the inherited
 * parser segment by segment, so no parsing code is duplicated.
 * <p>
 * Every feature is opt-in: if a pattern attribute is unset, nothing extra is logged.
 * Because placement is controlled entirely through patterns, parameters and bodies can
 * be appended to a single line (e.g. {@code patternEnd1="%P %J"}) or emitted as
 * separate lines ({@code patternEnd1="%P"} plus {@code patternEnd2="%J"}).
 * <p>
 * Warning: {@code %P} will log credentials submitted in login forms. Use conditional
 * logging ({@code conditionUnless}/{@code conditionIf}, inherited from AccessLogValve)
 * or exclude such paths at the log-analysis stage.
 */
public class ExpandedAccessLogValve extends AccessLogValve {

    private static final Log log = LogFactory.getLog(ExpandedAccessLogValve.class);

    /**
     * Request attribute name under which the body-caching request wrapper is stored, so
     * that {@link RequestBodyElement} can find the captured body at logging time.
     */
    static final String BODY_CACHE_ATTRIBUTE = "xpusostomos.tomcat.valves.bodyCache";

    /** Pattern code that renders the request parameters (form fields). */
    public static final char FORM_PARAMS_CODE = 'P';

    /** Pattern code that renders the captured request body. */
    public static final char BODY_CODE = 'J';

    /** Default value of the {@code bodyContentTypes} attribute. */
    private static final String DEFAULT_BODY_CONTENT_TYPES = "application/json, *+json";

    /** Default maximum number of body bytes captured for logging. */
    private static final int DEFAULT_MAX_BODY_LOG_SIZE = 4096;

    // -------------------------------------------------------- Instance Variables

    private volatile String patternBeg1 = null;
    private volatile String patternBeg2 = null;
    private volatile String patternBeg3 = null;
    private volatile String patternEnd1 = null;
    private volatile String patternEnd2 = null;
    private volatile String patternEnd3 = null;

    private volatile AccessLogElement[] patternBeg1Elements = null;
    private volatile AccessLogElement[] patternBeg2Elements = null;
    private volatile AccessLogElement[] patternBeg3Elements = null;
    private volatile AccessLogElement[] patternEnd1Elements = null;
    private volatile AccessLogElement[] patternEnd2Elements = null;
    private volatile AccessLogElement[] patternEnd3Elements = null;

    /** Cached elements from the auxiliary patterns, primed at the start of each request. */
    private volatile CachedElement[] auxiliaryCachedElements = new CachedElement[0];

    /** True if any auxiliary pattern contains {@code %J} (the main pattern is checked per request). */
    private volatile boolean bodyPatternInAuxiliaryPatterns = false;

    private volatile String bodyContentTypes = DEFAULT_BODY_CONTENT_TYPES;
    private volatile int maxBodyLogSize = DEFAULT_MAX_BODY_LOG_SIZE;

    // ---------------------------------------------------------- Constructors

    /**
     * Constructs a new ExpandedAccessLogValve. All patterns default to unset, so an
     * unconfigured valve logs nothing, exactly like the stock AccessLogValve.
     */
    public ExpandedAccessLogValve() {
        super();
    }

    // ------------------------------------------------------ Property accessors

    public String getPatternBeg1() {
        return patternBeg1;
    }

    public void setPatternBeg1(String patternBeg1) {
        this.patternBeg1 = patternBeg1;
        this.patternBeg1Elements = compileAuxiliaryPattern(patternBeg1);
        rebuildAuxiliaryElements();
    }

    public String getPatternBeg2() {
        return patternBeg2;
    }

    public void setPatternBeg2(String patternBeg2) {
        this.patternBeg2 = patternBeg2;
        this.patternBeg2Elements = compileAuxiliaryPattern(patternBeg2);
        rebuildAuxiliaryElements();
    }

    public String getPatternBeg3() {
        return patternBeg3;
    }

    public void setPatternBeg3(String patternBeg3) {
        this.patternBeg3 = patternBeg3;
        this.patternBeg3Elements = compileAuxiliaryPattern(patternBeg3);
        rebuildAuxiliaryElements();
    }

    public String getPatternEnd1() {
        return patternEnd1;
    }

    public void setPatternEnd1(String patternEnd1) {
        this.patternEnd1 = patternEnd1;
        this.patternEnd1Elements = compileAuxiliaryPattern(patternEnd1);
        rebuildAuxiliaryElements();
    }

    public String getPatternEnd2() {
        return patternEnd2;
    }

    public void setPatternEnd2(String patternEnd2) {
        this.patternEnd2 = patternEnd2;
        this.patternEnd2Elements = compileAuxiliaryPattern(patternEnd2);
        rebuildAuxiliaryElements();
    }

    public String getPatternEnd3() {
        return patternEnd3;
    }

    public void setPatternEnd3(String patternEnd3) {
        this.patternEnd3 = patternEnd3;
        this.patternEnd3Elements = compileAuxiliaryPattern(patternEnd3);
        rebuildAuxiliaryElements();
    }

    /**
     * Returns the comma-separated content types whose request bodies are captured for
     * {@code %J}. Supports exact types ({@code application/json}), the wildcard
     * {@code *} (any body) and the suffix wildcard {@code *+json} (any type ending in
     * {@code +json}).
     */
    public String getBodyContentTypes() {
        return bodyContentTypes;
    }

    public void setBodyContentTypes(String bodyContentTypes) {
        this.bodyContentTypes = bodyContentTypes;
    }

    /** Returns the maximum number of body bytes captured per request. */
    public int getMaxBodyLogSize() {
        return maxBodyLogSize;
    }

    public void setMaxBodyLogSize(int maxBodyLogSize) {
        this.maxBodyLogSize = maxBodyLogSize;
    }

    // -------------------------------------------------------- Public Methods

    /**
     * Logs the auxiliary "beginning of request" lines, prepares the request body
     * wrapper if required, then lets the standard valve machinery continue the
     * pipeline. Called once per pipeline dispatch; start lines are only emitted for
     * original (REQUEST) dispatches so async, error and forward dispatches do not
     * duplicate them.
     */
    @Override
    public void invoke(Request request, Response response) throws java.io.IOException, javax.servlet.ServletException {
        /*
         * Mirrors AbstractAccessLogValve.invoke(): prime TLS attributes and the
         * cached elements (for patterns containing %h, %A, %p and friends), extended
         * to the auxiliary patterns. The unconditional certificate attribute read is
         * the TLS handling of the parent valve (it is a no-op unless a TLS pattern
         * element is in use).
         */
        request.getAttribute(Globals.CERTIFICATES_ATTR);
        if (cachedElements != null) {
            for (CachedElement element : cachedElements) {
                element.cache(request);
            }
        }
        for (CachedElement element : auxiliaryCachedElements) {
            element.cache(request);
        }
        if (request.getDispatcherType() == DispatcherType.REQUEST) {
            emitBegLines(request, response);
            installBodyWrapper(request);
        }
        getNext().invoke(request, response);
    }

    /**
     * Called by the container exactly once per request when it completes (including
     * async and error completions). Renders the standard main pattern line via the
     * parent, then renders the auxiliary "end of request" lines.
     */
    @Override
    public void log(Request request, Response response, long time) {
        super.log(request, response, time);
        if (!isLoggable(request)) {
            return;
        }
        long start = request.getCoyoteRequest().getStartTime();
        Date date = new Date(start + time);
        emitLines(date, request, response, time, patternEnd1Elements, patternEnd2Elements, patternEnd3Elements);
    }

    /**
     * Suppresses blank lines, so that a valve configured with an empty pattern (or a
     * pattern that renders empty for a particular request) does not produce empty log
     * lines. Everything else is delegated to the file handling of the parent.
     */
    @Override
    public void log(CharArrayWriter message) {
        if (message.size() == 0) {
            return;
        }
        super.log(message);
    }

    // -------------------------------------------------------- Protected Methods

    /**
     * Compiles the main pattern. Called by the parent whenever {@code setPattern} is
     * invoked. Recognises the extra {@code %P} and {@code %J} codes and delegates
     * everything else to the inherited parser.
     */
    @Override
    protected AccessLogElement[] createLogElements() {
        return compileExpandedPattern(this.pattern);
    }

    // -------------------------------------------------------- Private Methods

    /**
     * Compiles one auxiliary pattern into log elements, expanding the
     * {@code common}/{@code combined} aliases the same way the parent does.
     */
    private AccessLogElement[] compileAuxiliaryPattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return null;
        }
        String expanded = pattern;
        if (Constants.AccessLog.COMMON_ALIAS.equals(pattern)) {
            expanded = Constants.AccessLog.COMMON_PATTERN;
        } else if (Constants.AccessLog.COMBINED_ALIAS.equals(pattern)) {
            expanded = Constants.AccessLog.COMBINED_PATTERN;
        }
        return compileExpandedPattern(expanded);
    }

    /**
     * Compiles a pattern into log elements. Identical in behaviour to the inherited
     * parser except that the additional codes {@code %P} (form parameters) and
     * {@code %J} (request body) are recognised. The pattern is split into segments
     * around those codes; each literal segment is compiled by the inherited parser
     * (with the {@code this.pattern} field temporarily pointed at the segment), so
     * every standard code and construct keeps working unchanged.
     */
    private AccessLogElement[] compileExpandedPattern(String pattern) {
        if (pattern == null) {
            return null;
        }
        List<AccessLogElement> elements = new ArrayList<>();
        StringBuilder segment = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char ch = pattern.charAt(i);
            if (ch == '%' && i + 1 < pattern.length()) {
                char code = pattern.charAt(i + 1);
                if (code == '%') {
                    // Escaped percent - leave for the inherited parser
                    segment.append("%%");
                    i += 2;
                    continue;
                }
                if (code == FORM_PARAMS_CODE || code == BODY_CODE) {
                    if (segment.length() > 0) {
                        addSegment(elements, segment.toString());
                        segment.setLength(0);
                    }
                    elements.add(code == FORM_PARAMS_CODE ? new FormParametersElement() : new RequestBodyElement());
                    i += 2;
                    continue;
                }
            }
            segment.append(ch);
            i++;
        }
        if (segment.length() > 0) {
            addSegment(elements, segment.toString());
        }
        return elements.toArray(new AccessLogElement[0]);
    }

    /** Compiles one literal pattern segment with the inherited parser. */
    private void addSegment(List<AccessLogElement> elements, String segment) {
        String savedPattern = this.pattern;
        this.pattern = segment;
        try {
            AccessLogElement[] compiled = super.createLogElements();
            if (compiled != null) {
                Collections.addAll(elements, compiled);
            }
        } finally {
            this.pattern = savedPattern;
        }
    }

    /** Rebuilds the merged cached-element list and body-pattern flag for the auxiliary patterns. */
    private void rebuildAuxiliaryElements() {
        List<AccessLogElement[]> all = new ArrayList<>();
        all.add(patternBeg1Elements);
        all.add(patternBeg2Elements);
        all.add(patternBeg3Elements);
        all.add(patternEnd1Elements);
        all.add(patternEnd2Elements);
        all.add(patternEnd3Elements);

        List<CachedElement> cached = new ArrayList<>();
        boolean hasBodyElement = false;
        for (AccessLogElement[] elements : all) {
            if (elements == null) {
                continue;
            }
            for (AccessLogElement element : elements) {
                if (element instanceof CachedElement) {
                    cached.add((CachedElement) element);
                }
                if (element instanceof RequestBodyElement) {
                    hasBodyElement = true;
                }
            }
        }
        this.auxiliaryCachedElements = cached.toArray(new CachedElement[0]);
        this.bodyPatternInAuxiliaryPatterns = hasBodyElement;
    }

    /** True if any pattern (main or auxiliary) contains a {@code %J} element. */
    private boolean hasBodyElement() {
        if (bodyPatternInAuxiliaryPatterns) {
            return true;
        }
        AccessLogElement[] mainElements = this.logElements;
        if (mainElements != null) {
            for (AccessLogElement element : mainElements) {
                if (element instanceof RequestBodyElement) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The same conditions the parent applies before logging an end-of-request line,
     * so that start and end lines appear together.
     */
    private boolean isLoggable(Request request) {
        if (!getEnabled() || !getState().isAvailable()) {
            return false;
        }
        if (condition != null && null != request.getRequest().getAttribute(condition)) {
            return false;
        }
        if (conditionIf != null && null == request.getRequest().getAttribute(conditionIf)) {
            return false;
        }
        return true;
    }

    private void emitBegLines(Request request, Response response) {
        if (!isLoggable(request)) {
            return;
        }
        Date date = new Date(request.getCoyoteRequest().getStartTime());
        emitLines(date, request, response, 0, patternBeg1Elements, patternBeg2Elements, patternBeg3Elements);
    }

    private void emitLines(Date date, Request request, Response response, long time, AccessLogElement[]... patterns) {
        for (AccessLogElement[] patternElements : patterns) {
            if (patternElements == null || patternElements.length == 0) {
                continue;
            }
            CharArrayWriter buf = new CharArrayWriter(128);
            for (AccessLogElement element : patternElements) {
                element.addElement(buf, date, request, response, time);
            }
            if (buf.size() > 0) {
                log(buf);
            }
        }
    }

    /**
     * Installs the body-caching wrapper when body logging is configured and the
     * request has a body with a matching content type. Uses the documented
     * {@link Request#setRequest(HttpServletRequest)} extension hook; the wrapper
     * delegates everything unchanged and only tees reads of the request body.
     */
    private void installBodyWrapper(Request request) {
        if (!hasBodyElement()) {
            return;
        }
        String contentType = request.getContentType();
        if (contentType == null || !matchesBodyContentType(contentType)) {
            return;
        }
        if (request.getContentLengthLong() == 0) {
            return;
        }
        HttpServletRequest applicationRequest = request.getRequest();
        BodyCachingRequestWrapper wrapper = new BodyCachingRequestWrapper(applicationRequest, maxBodyLogSize);
        request.setRequest(wrapper);
        request.setAttribute(BODY_CACHE_ATTRIBUTE, wrapper);
    }

    private boolean matchesBodyContentType(String contentType) {
        String mime = contentType.toLowerCase(Locale.ROOT);
        int semicolon = mime.indexOf(';');
        if (semicolon >= 0) {
            mime = mime.substring(0, semicolon).trim();
        }
        String[] contentTypes = bodyContentTypes.split(",");
        for (String configured : contentTypes) {
            String token = configured.trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            if (token.equals("*") || token.equals(mime)) {
                return true;
            }
            if (token.equals("*+json") && mime.endsWith("+json")) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------- Pattern Elements

    /**
     * Element for the {@code %P} code: renders the request parameters as
     * {@code name=value&name=value}, URL-decoded and escaped, or {@code -} when the
     * request has no parameters.
     * <p>
     * Note: reading the parameters at the start of a request (i.e. using {@code %P}
     * in a beginning pattern) forces Tomcat to parse the parameters early. Tomcat
     * caches the result so applications using getParameter() are unaffected, but
     * applications that read the raw body stream of form posts themselves will see an
     * empty stream.
     */
    protected class FormParametersElement implements AccessLogElement {

        @Override
        public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
            Map<String, String[]> parameters = request.getParameterMap();
            if (parameters == null || parameters.isEmpty()) {
                buf.append('-');
                return;
            }
            boolean first = true;
            for (Map.Entry<String, String[]> entry : parameters.entrySet()) {
                String[] values = entry.getValue();
                if (values == null || values.length == 0) {
                    if (!first) {
                        buf.append('&');
                    }
                    buf.append(escape(entry.getKey())).append('=').append('-');
                    first = false;
                    continue;
                }
                for (String value : values) {
                    if (!first) {
                        buf.append('&');
                    }
                    buf.append(escape(entry.getKey())).append('=').append(escape(value == null ? "" : value));
                    first = false;
                }
            }
        }
    }

    /**
     * Element for the {@code %J} code: renders the request body captured by the
     * body-caching wrapper, escaped, and truncated with a marker when it exceeded
     * {@code maxBodyLogSize}. Renders {@code -} when there is nothing to log and
     * {@code (not read)} when a body was present but the application never read it.
     */
    protected class RequestBodyElement implements AccessLogElement {

        @Override
        public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
            Object cached = request.getAttribute(BODY_CACHE_ATTRIBUTE);
            if (!(cached instanceof BodyCachingRequestWrapper)) {
                buf.append('-');
                return;
            }
            BodyCachingRequestWrapper wrapper = (BodyCachingRequestWrapper) cached;
            byte[] data = wrapper.getCache().getBytes();
            if (data.length == 0) {
                buf.append(request.getContentLengthLong() > 0 ? "(not read)" : "-");
                return;
            }
            buf.append(escape(new String(data, bodyEncoding(request))));
            if (wrapper.getCache().isTruncated()) {
                buf.append("...[truncated]");
            }
        }

        private Charset bodyEncoding(Request request) {
            String name = request.getCharacterEncoding();
            if (name != null) {
                try {
                    return Charset.forName(name);
                } catch (IllegalArgumentException e) {
                    // Fall back to UTF-8, the default for JSON
                }
            }
            return StandardCharsets.UTF_8;
        }
    }

    // -------------------------------------------------------- Utilities

    /**
     * Escapes a value so it cannot break the log line format: backslash, double
     * quote, carriage return, line feed and tab are written as backslash escape
     * sequences, and other control characters as {@code \xNN}. This prevents log
     * forging by request content.
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = null;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            String replacement = null;
            switch (ch) {
                case '\\':
                    replacement = "\\\\";
                    break;
                case '"':
                    replacement = "\\\"";
                    break;
                case '\r':
                    replacement = "\\r";
                    break;
                case '\n':
                    replacement = "\\n";
                    break;
                case '\t':
                    replacement = "\\t";
                    break;
                default:
                    if (ch < 0x20 || ch == 0x7f) {
                        replacement = String.format("\\x%02x", (int) ch);
                    }
                    break;
            }
            if (replacement != null && escaped == null) {
                escaped = new StringBuilder(value.length() + 16);
                escaped.append(value, 0, i);
            }
            if (replacement != null) {
                escaped.append(replacement);
            } else if (escaped != null) {
                escaped.append(ch);
            }
        }
        return escaped == null ? value : escaped.toString();
    }
}