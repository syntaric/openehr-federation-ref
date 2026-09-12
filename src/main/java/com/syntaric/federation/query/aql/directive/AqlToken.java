// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.directive;

/**
 * One lexical token of an AQL text, with its [start, end) offsets in the source.
 */
public record AqlToken(Type type, String text, int start, int end) {

    public enum Type {
        /** Bare word: keyword, identifier or RM type name. */
        WORD,
        /** Single-quoted or double-quoted string literal, quotes included in text. */
        STRING,
        /** Number literal. */
        NUMBER,
        /** Any single punctuation character: [ ] ( ) , / = < > etc. */
        SYMBOL
    }

    /** Case-insensitive keyword match (only meaningful for WORD tokens). */
    public boolean isWord(final String word) {
        return type == Type.WORD && text.equalsIgnoreCase(word);
    }

    /** The unquoted value of a STRING token. */
    public String stringValue() {
        return text.substring(1, text.length() - 1);
    }
}
