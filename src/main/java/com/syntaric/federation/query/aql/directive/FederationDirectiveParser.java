// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.directive;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Extracts and strips the federation-only {@code FROM ENDPOINT p [ "id", … ]} /
 * {@code FROM ORGANISATION o [ … ]} directive (spec §8.1) and any SELECT
 * projections over the directive alias ({@code p/id}, {@code p/system_id},
 * {@code p/organisation}, {@code p/url}; spec §8.3/§9.2). The remainder is
 * standard AQL a node parser will accept.
 *
 * <p>Fail-closed: any use of the directive alias outside the supported
 * projection forms is rejected with 400.
 */
public final class FederationDirectiveParser {

    private FederationDirectiveParser() {
    }

    public static PreprocessedAql parse(final String aql) {
        final List<AqlToken> tokens = AqlTokenizer.tokenize(aql);

        final int directiveKeyword = findDirectiveKeyword(tokens);
        if (directiveKeyword < 0) {
            return PreprocessedAql.withoutDirective(aql);
        }

        final boolean endpointForm = tokens.get(directiveKeyword).isWord("ENDPOINT");
        int cursor = directiveKeyword + 1;

        String alias = null;
        if (cursor < tokens.size() && tokens.get(cursor).type() == AqlToken.Type.WORD
                && !"[".equals(tokens.get(cursor).text())) {
            alias = tokens.get(cursor).text();
            cursor++;
        }

        expectSymbol(tokens, cursor, "[");
        cursor++;
        final LinkedHashSet<String> ids = new LinkedHashSet<>();
        while (cursor < tokens.size() && tokens.get(cursor).type() == AqlToken.Type.STRING) {
            ids.add(tokens.get(cursor).stringValue());
            cursor++;
            if (cursor < tokens.size() && ",".equals(tokens.get(cursor).text())) {
                cursor++;
            } else {
                break;
            }
        }
        if (ids.isEmpty()) {
            throw directiveError("directive endpoint list is empty");
        }
        expectSymbol(tokens, cursor, "]");
        cursor++;

        if (cursor >= tokens.size() || !tokens.get(cursor).isWord("CONTAINS")) {
            throw directiveError("directive must be followed by CONTAINS EHR …");
        }
        // Strip "[ENDPOINT|ORGANISATION] [alias] [ ... ] CONTAINS", keeping FROM itself.
        final int stripStart = tokens.get(directiveKeyword).start();
        final int stripEnd = tokens.get(cursor).end();

        final List<int[]> removals = new ArrayList<>();
        removals.add(new int[]{stripStart, stripEnd});

        final List<PreprocessedAql.EndpointProjection> projections = alias == null
                ? List.of()
                : stripProjections(tokens, alias, removals);

        guardAliasUnusedElsewhere(tokens, alias, removals);

        final String stripped = removeRanges(aql, removals);
        final LinkedHashSet<String> endpointIds = endpointForm ? ids : new LinkedHashSet<>();
        final LinkedHashSet<String> organisationIds = endpointForm ? new LinkedHashSet<>() : ids;
        return new PreprocessedAql(stripped, true, endpointIds, organisationIds, projections);
    }

    /** Index of the ENDPOINT/ORGANISATION keyword directly following top-level FROM. */
    private static int findDirectiveKeyword(final List<AqlToken> tokens) {
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (tokens.get(i).isWord("FROM")) {
                final AqlToken next = tokens.get(i + 1);
                if (next.isWord("ENDPOINT") || next.isWord("ORGANISATION")) {
                    return i + 1;
                }
                return -1; // ordinary FROM EHR …
            }
        }
        return -1;
    }

    /**
     * Removes SELECT-list items that project the directive alias, recording each
     * as an {@link PreprocessedAql.EndpointProjection} with its original position.
     */
    private static List<PreprocessedAql.EndpointProjection> stripProjections(
            final List<AqlToken> tokens, final String alias, final List<int[]> removals) {
        int selectIdx = -1;
        int fromIdx = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (selectIdx < 0 && tokens.get(i).isWord("SELECT")) {
                selectIdx = i;
            } else if (selectIdx >= 0 && tokens.get(i).isWord("FROM")) {
                fromIdx = i;
                break;
            }
        }
        if (selectIdx < 0 || fromIdx < 0) {
            throw directiveError("query has no SELECT … FROM structure");
        }

        // Split the select list into top-level comma-separated items.
        final List<List<AqlToken>> items = new ArrayList<>();
        List<AqlToken> current = new ArrayList<>();
        int depth = 0;
        final List<int[]> itemRanges = new ArrayList<>();
        int itemStartTok = selectIdx + 1;
        for (int i = selectIdx + 1; i < fromIdx; i++) {
            final AqlToken t = tokens.get(i);
            if (t.type() == AqlToken.Type.SYMBOL && ("(".equals(t.text()) || "[".equals(t.text()))) {
                depth++;
            } else if (t.type() == AqlToken.Type.SYMBOL && (")".equals(t.text()) || "]".equals(t.text()))) {
                depth--;
            }
            if (depth == 0 && t.type() == AqlToken.Type.SYMBOL && ",".equals(t.text())) {
                items.add(current);
                itemRanges.add(new int[]{itemStartTok, i});
                current = new ArrayList<>();
                itemStartTok = i + 1;
            } else {
                current.add(t);
            }
        }
        items.add(current);
        itemRanges.add(new int[]{itemStartTok, fromIdx});

        // "DISTINCT" may lead the first item; it is not part of the expression.
        final List<PreprocessedAql.EndpointProjection> projections = new ArrayList<>();
        final List<Integer> removedItems = new ArrayList<>();
        for (int idx = 0; idx < items.size(); idx++) {
            final List<AqlToken> item = new ArrayList<>(items.get(idx));
            if (!item.isEmpty() && item.get(0).isWord("DISTINCT")) {
                item.remove(0);
            }
            if (item.isEmpty()) {
                throw directiveError("empty SELECT item");
            }
            final boolean referencesAlias = item.stream().anyMatch(t -> t.isWord(alias));
            if (!referencesAlias) {
                continue;
            }
            projections.add(parseProjection(item, alias, idx));
            removedItems.add(idx);
        }

        // Remove each projected item plus one adjacent comma.
        for (int k = 0; k < removedItems.size(); k++) {
            int idx = removedItems.get(k);
            final int[] range = itemRanges.get(idx);
            final int startTok = range[0];
            final int endTokExclusive = range[1];
            int startOffset = tokens.get(startTok).start();
            int endOffset = tokens.get(endTokExclusive - 1).end();
            if (endTokExclusive < fromIdx && ",".equals(tokens.get(endTokExclusive).text())) {
                endOffset = tokens.get(endTokExclusive).end();          // trailing comma
            } else if (startTok > selectIdx + 1 && ",".equals(tokens.get(startTok - 1).text())) {
                startOffset = tokens.get(startTok - 1).start();          // leading comma
            } else if (items.size() == removedItems.size()) {
                throw directiveError("SELECT must include at least one non-ENDPOINT column");
            }
            removals.add(new int[]{startOffset, endOffset});
        }
        return projections;
    }

    /** Accepts exactly {@code alias/attr} or {@code alias/attr AS name}. */
    private static PreprocessedAql.EndpointProjection parseProjection(final List<AqlToken> item, final String alias, final int position) {
        final boolean shortForm = item.size() == 3;
        final boolean aliasedForm = item.size() == 5 && item.get(3).isWord("AS")
                && item.get(4).type() == AqlToken.Type.WORD;
        if (!(shortForm || aliasedForm)
                || !item.get(0).isWord(alias)
                || !"/".equals(item.get(1).text())
                || item.get(2).type() != AqlToken.Type.WORD) {
            throw directiveError("unsupported use of directive alias '" + alias + "' in SELECT");
        }
        final String attrName = item.get(2).text().toLowerCase();
        final PreprocessedAql.EndpointProjection.Attribute attribute = switch (attrName) {
            case "id" -> PreprocessedAql.EndpointProjection.Attribute.ID;
            case "system_id" -> PreprocessedAql.EndpointProjection.Attribute.SYSTEM_ID;
            case "organisation", "organization" -> PreprocessedAql.EndpointProjection.Attribute.ORGANISATION;
            case "url" -> PreprocessedAql.EndpointProjection.Attribute.URL;
            default -> throw directiveError("unknown ENDPOINT attribute '" + attrName + "'");
        };
        final String columnName = aliasedForm ? item.get(4).text() : attribute.defaultColumnName();
        return new PreprocessedAql.EndpointProjection(attribute, columnName, position);
    }

    /** The alias must not survive into WHERE / ORDER BY — fail closed. */
    private static void guardAliasUnusedElsewhere(final List<AqlToken> tokens, final String alias, final List<int[]> removals) {
        if (alias == null) {
            return;
        }
        for (int i = 0; i < tokens.size(); i++) {
            final AqlToken t = tokens.get(i);
            if (!t.isWord(alias)) {
                continue;
            }
            boolean removed = false;
            for (final int[] r : removals) {
                if (t.start() >= r[0] && t.end() <= r[1]) {
                    removed = true;
                    break;
                }
            }
            final boolean pathUse = i + 1 < tokens.size() && "/".equals(tokens.get(i + 1).text());
            if (!removed && pathUse) {
                throw new FederationException(FedErrorCode.FED_QUERY_UNSUPPORTED,
                        "ENDPOINT alias '" + alias + "' may only be used in SELECT projections");
            }
        }
    }

    private static String removeRanges(final String aql, final List<int[]> removals) {
        removals.sort((a, b) -> Integer.compare(a[0], b[0]));
        final StringBuilder sb = new StringBuilder();
        int pos = 0;
        for (final int[] r : removals) {
            if (r[0] > pos) {
                sb.append(aql, pos, r[0]);
            }
            pos = Math.max(pos, r[1]);
        }
        sb.append(aql, pos, aql.length());
        return sb.toString().replaceAll("[ \\t]+", " ").trim();
    }

    private static void expectSymbol(final List<AqlToken> tokens, final int idx, final String symbol) {
        if (idx >= tokens.size() || !symbol.equals(tokens.get(idx).text())) {
            throw directiveError("expected '" + symbol + "' in federation directive");
        }
    }

    private static FederationException directiveError(final String message) {
        return new FederationException(FedErrorCode.FED_AQL_INVALID, "Invalid federation directive: " + message);
    }
}
