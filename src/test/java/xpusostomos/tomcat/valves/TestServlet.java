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
import java.io.IOException;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Endpoints used by the integration tests. */
public class TestServlet extends HttpServlet {

    static String readBody(HttpServletRequest request) throws IOException {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            int ch;
            while ((ch = reader.read()) >= 0) {
                body.append((char) ch);
            }
        }
        return body.toString();
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        switch (path) {
            case "/hello":
                response.getWriter().print("hello");
                break;
            case "/form":
                response.getWriter().print("a=" + request.getParameter("a"));
                break;
            case "/json":
                response.getWriter().print(readBody(request));
                break;
            case "/json-unread":
                // Deliberately does not read the body
                response.getWriter().print("ignored");
                break;
            case "/text":
                readBody(request);
                response.getWriter().print("text");
                break;
            case "/multipart":
                response.getWriter().print("parts=" + request.getParts().size());
                break;
            case "/asyncjson":
                final HttpServletRequest asyncRequest = request;
                final AsyncContext async = request.startAsync();
                new Thread(() -> {
                    try {
                        String body = readBody(asyncRequest);
                        async.getResponse().getWriter().print("async:" + body);
                    } catch (IOException e) {
                        // Response already broken; nothing further to do
                    } finally {
                        async.complete();
                    }
                }, "test-async").start();
                break;
            default:
                response.sendError(404);
        }
    }
}