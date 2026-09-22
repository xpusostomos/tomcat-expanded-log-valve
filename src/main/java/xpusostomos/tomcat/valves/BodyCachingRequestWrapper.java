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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

/**
 * Wraps the application request and tees every byte read from the request body into a
 * bounded, in-memory buffer so that a valve can log the body without consuming it. The
 * application continues to read the body exactly as it would have without the wrapper.
 * <p>
 * The approach mirrors Spring's {@code ContentCachingRequestWrapper}: only bytes actually
 * read by the application are captured, so a body that the application never reads
 * cannot be logged. The cache is thread-safe since the body may be consumed on a
 * different thread when the request goes asynchronous.
 */
class BodyCachingRequestWrapper extends HttpServletRequestWrapper {

    private final BodyCache cache;

    BodyCachingRequestWrapper(HttpServletRequest request, int maxBodyLogSize) {
        super(request);
        this.cache = new BodyCache(maxBodyLogSize);
    }

    BodyCache getCache() {
        return cache;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        return new CachingServletInputStream(super.getInputStream(), cache);
    }

    @Override
    public BufferedReader getReader() throws IOException {
        Charset charset = StandardCharsets.ISO_8859_1;
        String encoding = getCharacterEncoding();
        if (encoding != null) {
            try {
                charset = Charset.forName(encoding);
            } catch (IllegalArgumentException e) {
                // Keep the servlet default
            }
        }
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    /** Bounded in-memory sink for the teed request body. */
    static final class BodyCache {
        private final int maxSize;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean truncated = false;

        BodyCache(int maxSize) {
            this.maxSize = Math.max(0, maxSize);
        }

        synchronized void writeByte(int b) {
            if (buffer.size() < maxSize) {
                buffer.write(b);
            } else {
                truncated = true;
            }
        }

        synchronized void write(byte[] b, int off, int len) {
            int allowed = maxSize - buffer.size();
            if (allowed <= 0) {
                truncated = true;
                return;
            }
            int toCopy = Math.min(allowed, len);
            buffer.write(b, off, toCopy);
            if (toCopy < len) {
                truncated = true;
            }
        }

        synchronized byte[] getBytes() {
            return buffer.toByteArray();
        }

        synchronized boolean isTruncated() {
            return truncated;
        }

        synchronized boolean isEmpty() {
            return buffer.size() == 0;
        }
    }

    /** ServletInputStream that copies everything read through it into a {@link BodyCache}. */
    private static final class CachingServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final BodyCache cache;

        CachingServletInputStream(ServletInputStream delegate, BodyCache cache) {
            this.delegate = delegate;
            this.cache = cache;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                cache.writeByte(b);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                cache.write(b, off, n);
            }
            return n;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}