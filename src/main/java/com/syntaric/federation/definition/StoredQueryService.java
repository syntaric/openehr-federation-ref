// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.definition;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The gateway-held stored-query registry (§12.7, N44): store once, immutable
 * per version, resolve by name to the latest or to an exact version.
 *
 * <p>CRUD over AQL <em>text</em> only. This service neither parses nor
 * executes a definition — the controller validates on the way in and the query
 * engine expands on the way out — so the package stays a leaf with no reach
 * into the AQL pipeline, which {@code ArchitectureTest.definitionStaysALeaf}
 * pins.
 */
@Service
public class StoredQueryService {

    private final StoredQueryRepository repository;
    private final Clock clock;

    @Autowired
    public StoredQueryService(final StoredQueryRepository repository) {
        this(repository, Clock.systemUTC());
    }

    /** For tests that pin {@code saved}; Spring takes the single-argument constructor above. */
    StoredQueryService(final StoredQueryRepository repository, final Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Stores a definition under a {@code name/version} the registry does not yet
     * hold. A second {@code PUT} to the same pair is refused with 409, never
     * applied: a stored version is immutable (§12.7), so a client that wants a
     * different definition stores a new version. The pre-check gives the common
     * case a clean error; the {@code UNIQUE} constraint decides the race.
     */
    @Transactional
    public StoredQueryMetadata store(final QualifiedQueryName name, final SemVer version, final String aql) {
        if (repository.findByNameAndVersion(name.qualified(), version.toString()).isPresent()) {
            throw exists(name, version);
        }
        try {
            // Microseconds: what TIMESTAMPTZ keeps, so the PUT response and a
            // later GET report the same `saved` rather than differing in digits
            // the column never held.
            final Instant savedAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
            final StoredQuery saved = repository.save(
                    StoredQuery.of(name.qualified(), version.toString(), aql, savedAt));
            return StoredQueryMetadata.of(saved);
        } catch (final DuplicateKeyException e) {
            throw exists(name, version);
        }
    }

    public Optional<StoredQuery> find(final QualifiedQueryName name, final SemVer version) {
        return repository.findByNameAndVersion(name.qualified(), version.toString());
    }

    /** The highest semver stored under {@code name}, which is what an unversioned invocation means. */
    public Optional<StoredQuery> latest(final QualifiedQueryName name) {
        return repository.findByName(name.qualified()).stream()
                .max(Comparator.comparing(stored -> SemVer.parse(stored.version())));
    }

    /** Every version stored under {@code name}, oldest first — the ITS-REST list form. */
    public List<StoredQueryMetadata> versions(final QualifiedQueryName name) {
        return repository.findByName(name.qualified()).stream()
                .sorted(Comparator.comparing(stored -> SemVer.parse(stored.version())))
                .map(StoredQueryMetadata::of)
                .toList();
    }

    /** The definition an invocation names, or 404: exact version when given, else the latest. */
    public StoredQuery require(final QualifiedQueryName name, final SemVer version) {
        final Optional<StoredQuery> found = version == null ? latest(name) : find(name, version);
        return found.orElseThrow(() -> new FederationException(FedErrorCode.FED_NOT_FOUND,
                version == null
                        ? "No stored query named '" + name + "'"
                        : "No stored query named '" + name + "' at version " + version));
    }

    private static FederationException exists(final QualifiedQueryName name, final SemVer version) {
        return new FederationException(FedErrorCode.FED_STORED_QUERY_EXISTS,
                "Stored query '" + name + "' already holds version " + version
                        + "; a stored version is immutable, store a new version instead (§12.7)");
    }
}
