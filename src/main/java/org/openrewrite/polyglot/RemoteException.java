/*
 * Copyright 2021 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.polyglot;

import lombok.RequiredArgsConstructor;
import org.openrewrite.internal.lang.Nullable;

import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;

/**
 * A serialized exception DTO that contains enough information about an exception caused by a
 * remote process to render a meaningful message, list of suggestions, and optionally a stack trace.
 */
public class RemoteException extends RuntimeException {
    @Nullable
    private final String sanitizedStackTrace;

    private final String[] fixSuggestions;

    RemoteException(String message, @Nullable String sanitizedStackTrace, String[] fixSuggestions) {
        super(message);
        this.sanitizedStackTrace = sanitizedStackTrace;
        this.fixSuggestions = fixSuggestions;
    }

    public static Builder builder(String message) {
        return new Builder(message);
    }

    @RequiredArgsConstructor
    public static class Builder {
        private final String message;
        private final List<String> fixSuggestions = new ArrayList<>();

        @Nullable
        private String sanitizedStackTrace;

        public Builder cause(Throwable t, String... stackTracePrefixFilter) {
            this.sanitizedStackTrace = sanitizeStackTrace(t, stackTracePrefixFilter);
            return this;
        }

        public Builder fixSuggestions(String... fixSuggestions) {
            this.fixSuggestions.addAll(Arrays.asList(fixSuggestions));
            return this;
        }

        public RemoteException build() {
            return new RemoteException(message, sanitizedStackTrace, fixSuggestions.toArray(new String[0]));
        }
    }

    public static String sanitizeStackTrace(Throwable t, String... stackTracePrefixFilter) {
        StringJoiner sanitized = new StringJoiner("\n");

        int causeDepth = 0;
        for (Throwable tt = t; tt != null; tt = tt.getCause(), causeDepth++) {
            sanitized.add((causeDepth == 0 ? "" : "Caused by ") +
                          tt.getClass().getName() + ": " + tt.getLocalizedMessage());
            int i = 0;
            for (StackTraceElement stackTraceElement : tt.getStackTrace()) {
                String stackTraceClass = stackTraceElement.getClassName();
                if (stackTraceClass.startsWith("java.util.stream") ||
                    stackTraceClass.startsWith("java.net.Inet")) {
                    break;
                }
                for (String filter : stackTracePrefixFilter) {
                    if (stackTraceClass.startsWith(filter)) {
                        break;
                    }
                }
                if (i++ >= 8) {
                    sanitized.add("  ...");
                    break;
                }
                sanitized.add("  " + stackTraceElement);
            }
        }
        return sanitized.toString();
    }

    public String encode() {
        Base64.Encoder base64 = Base64.getEncoder();
        return base64.encodeToString(getMessage().getBytes(UTF_8)) + "\n" +
               (sanitizedStackTrace == null ? "null" : base64.encodeToString(sanitizedStackTrace.getBytes(UTF_8))) + "\n" +
               (fixSuggestions.length == 0 ? "null" : stream(fixSuggestions).map(s -> base64.encodeToString(s.getBytes(UTF_8))).collect(joining(",")));
    }

    public static RemoteException decode(String encoded) {
        Base64.Decoder base64 = Base64.getDecoder();
        String[] lines = encoded.split("\n");
        return new RemoteException(
                new String(base64.decode(lines[0]), UTF_8),
                lines[1].equals("null") ? null : new String(base64.decode(lines[1]), UTF_8),
                lines[2].equals("null") ? new String[0] : stream(lines[2].split(",")).map(s -> new String(base64.decode(s), UTF_8)).toArray(String[]::new)
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RemoteException that = (RemoteException) o;
        return Objects.equals(sanitizedStackTrace, that.sanitizedStackTrace) && Arrays.equals(fixSuggestions, that.fixSuggestions);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(sanitizedStackTrace);
        result = 31 * result + Arrays.hashCode(fixSuggestions);
        return result;
    }
}
