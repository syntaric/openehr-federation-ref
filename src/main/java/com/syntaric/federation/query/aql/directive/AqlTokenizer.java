// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.directive;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal string/comment-safe lexer over AQL text. It exists solely so the
 * federation directive ({@code FROM ENDPOINT p [...]}) can be located and
 * stripped without ever confusing directive keywords with identical text inside
 * string literals or comments. It is NOT an AQL grammar; full parsing is the
 * EHRbase SDK's job after stripping.
 *
 * <p>Comments ({@code -- ...} to end of line, {@code /* ... *&#47;}) are dropped
 * from the token stream but their offsets stay intact, so reconstruction by
 * offset keeps the original text.
 */
public final class AqlTokenizer {

    private AqlTokenizer() {
    }

    public static List<AqlToken> tokenize(final String aql) {
        final List<AqlToken> tokens = new ArrayList<>();
        int i = 0;
        final int n = aql.length();
        while (i < n) {
            final char c = aql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && aql.charAt(i + 1) == '-') {
                i = lineCommentEnd(aql, i);
            } else if (c == '/' && i + 1 < n && aql.charAt(i + 1) == '*') {
                i = blockCommentEnd(aql, i);
            } else if (c == '\'' || c == '"') {
                int end = stringEnd(aql, i, c);
                tokens.add(new AqlToken(AqlToken.Type.STRING, aql.substring(i, end), i, end));
                i = end;
            } else if (Character.isLetter(c) || c == '_') {
                int end = i;
                while (end < n && (Character.isLetterOrDigit(aql.charAt(end)) || aql.charAt(end) == '_')) {
                    end++;
                }
                tokens.add(new AqlToken(AqlToken.Type.WORD, aql.substring(i, end), i, end));
                i = end;
            } else if (Character.isDigit(c)) {
                int end = i;
                while (end < n && (Character.isDigit(aql.charAt(end)) || aql.charAt(end) == '.')) {
                    end++;
                }
                tokens.add(new AqlToken(AqlToken.Type.NUMBER, aql.substring(i, end), i, end));
                i = end;
            } else {
                tokens.add(new AqlToken(AqlToken.Type.SYMBOL, String.valueOf(c), i, i + 1));
                i++;
            }
        }
        return tokens;
    }

    private static int lineCommentEnd(final String aql, final int start) {
        final int end = aql.indexOf('\n', start);
        return end < 0 ? aql.length() : end + 1;
    }

    private static int blockCommentEnd(final String aql, final int start) {
        final int end = aql.indexOf("*/", start + 2);
        if (end < 0) {
            throw new FederationException(FedErrorCode.FED_AQL_INVALID, "Unterminated block comment in AQL");
        }
        return end + 2;
    }

    private static int stringEnd(final String aql, final int start, final char quote) {
        int i = start + 1;
        final int n = aql.length();
        while (i < n) {
            final char c = aql.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else {
                i++;
            }
        }
        throw new FederationException(FedErrorCode.FED_AQL_INVALID, "Unterminated string literal in AQL");
    }
}
