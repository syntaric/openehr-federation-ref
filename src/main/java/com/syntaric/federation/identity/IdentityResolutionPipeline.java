// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.identity;

import com.syntaric.federation.config.FederationProperties;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.identity.spi.LocalizationResult;
import com.syntaric.federation.identity.spi.PatientCrossReferenceService;
import com.syntaric.federation.identity.spi.PatientLocalizationService;
import com.syntaric.federation.identity.spi.PatientToken;
import com.syntaric.federation.registry.ResolutionBinding;
import com.syntaric.federation.registry.ResolutionBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Step 1 of the reference flow: localization → per-node cross-reference →
 * cached resolution bindings. Consent (N27) gates inclusion here: a binding
 * with {@code consent_status=denied} yields {@code consent-denied} without
 * calling the node.
 */
@Service
public class IdentityResolutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IdentityResolutionPipeline.class);

    private final PatientLocalizationService localization;
    private final PatientCrossReferenceService crossReference;
    private final ResolutionBindingRepository bindings;
    private final PatientRefHasher hasher;
    private final FederationProperties properties;

    public IdentityResolutionPipeline(final PatientLocalizationService localization,
                                      final PatientCrossReferenceService crossReference,
                                      final ResolutionBindingRepository bindings,
                                      final PatientRefHasher hasher,
                                      final FederationProperties properties) {
        this.localization = localization;
        this.crossReference = crossReference;
        this.bindings = bindings;
        this.hasher = hasher;
        this.properties = properties;
    }

    /** Resolves the patient at each target endpoint; never throws per node. */
    public List<NodeResolution> resolve(final PatientToken token, final List<EndpointDescriptor> targets) {
        final String refHash = hasher.hash(token);
        final LocalizationResult localized = localize(token, targets);
        final List<NodeResolution> results = new ArrayList<>();
        final Instant now = Instant.now();

        for (final EndpointDescriptor endpoint : targets) {
            // A consent-aware localizer's explicit refusal (N27a) is a consent
            // decision and is cached as one. Checked first: a node can be both
            // refused and absent from the candidate set, and the refusal is the
            // more specific fact.
            if (localized.consentDenied(endpoint.endpointId())) {
                results.add(consentDenied(endpoint, refHash, now,
                        "consent service denied inclusion at step 1"));
                continue;
            }
            if (localized.restrictsCandidates() && !localized.includes(endpoint.endpointId())) {
                // Not a candidate. §14.4: this is "holds no records here", not
                // a consent denial — so it is NOT written to
                // resolution_binding.consent_status, and it is not cached at
                // all. Caching a where-answer under a consent column is exactly
                // the collapse the spec forbids.
                results.add(new NodeResolution(endpoint.endpointId(), null,
                        NodeResolution.Status.NOT_LOCALIZED, localizationReason(localized)));
                continue;
            }
            results.add(resolveOne(token, endpoint, refHash, now));
        }
        return results;
    }

    // ---- localization (N4 / CP-5) -------------------------------------------

    /**
     * Runs the localization call under {@code federation.localization.timeout} and
     * turns whatever comes back — including a hang — into a
     * {@link LocalizationResult}.
     *
     * <p>The timeout is the point. {@code resolve} already executes inside the
     * clinical {@code TimeoutBudget}, so an unbounded call to a national index
     * would spend the fan-out's allowance and member nodes would start
     * reporting {@code time-out} for a reason that has nothing to do with them.
     * Bounding it here means a slow localizer fails as a localizer.
     */
    private LocalizationResult localize(final PatientToken token, final List<EndpointDescriptor> targets) {
        final FederationProperties.Localization config = properties.localization();
        if (config.mode() == FederationProperties.Localization.Mode.NONE) {
            return LocalizationResult.disabled();
        }

        final long startedAt = System.nanoTime();
        final LocalizationResult result = callWithTimeout(token, targets, config.timeout());
        final long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        return switch (result.outcome()) {
            case UNAVAILABLE -> onFailure(config, result, latencyMs);
            case DISABLED -> result;
            default -> {
                yield result;
            }
        };
    }

    /**
     * The backend is unreachable. {@code closed} — the default — queries no
     * node, because localization exists so that the federation does not learn
     * who asked about a patient, and falling back to ask-all discards exactly
     * that property. {@code ask-all} is the availability-over-privacy choice,
     * and it is audited and logged loudly precisely because it is silent to the
     * caller otherwise.
     *
     * <p>Note what this is <b>not</b>: an unreachable record-locator is an
     * availability failure, not a consent refusal. Nodes dropped here are
     * reported {@code excluded}, and nothing is written to
     * {@code consent_status} — a localizer that is merely down has not denied
     * anyone anything (§14.4).
     */
    private LocalizationResult onFailure(final FederationProperties.Localization config,
                                         final LocalizationResult failure, final long latencyMs) {
        final boolean askAll = config.onFailure() == FederationProperties.Localization.OnFailure.ASK_ALL;
        if (askAll) {
            log.warn("Localization backend {} unavailable ({}); falling back to ask-all — "
                            + "every member node now learns of this query",
                    config.mode().propertyValue(), failure.error());
            return LocalizationResult.disabled();
        }
        log.warn("Localization backend {} unavailable ({}); failing closed",
                config.mode().propertyValue(), failure.error());
        return failure.restrictsCandidates() ? failure : LocalizationResult.noRecords();
    }

    private LocalizationResult callWithTimeout(final PatientToken token, final List<EndpointDescriptor> targets,
                                               final Duration timeout) {
        final CompletableFuture<LocalizationResult> call =
                CompletableFuture.supplyAsync(() -> localization.localize(token, targets));
        try {
            final LocalizationResult result = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return result == null
                    ? LocalizationResult.unavailable("localization returned no result")
                    : result;
        } catch (final TimeoutException e) {
            call.cancel(true);
            return LocalizationResult.unavailable("localization timed out after " + timeout);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return LocalizationResult.unavailable("localization interrupted");
        } catch (final Exception e) {
            // An implementation is contracted not to throw; one that does must
            // still land on the on-failure policy rather than kill the query.
            return LocalizationResult.unavailable(rootMessage(e));
        }
    }

    /**
     * A consent authority refused this node at Step 1 (N27a).
     *
     * <p>Cached in {@code resolution_binding.consent_status} so a repeat query
     * inside the TTL need not re-ask the consent service. Only a genuine
     * refusal reaches here — a node that is merely not a candidate is
     * {@link NodeResolution.Status#NOT_LOCALIZED} and is not cached, because
     * "no records here" is not a consent decision (§14.4).
     *
     * <p>The pre-filter is an efficiency and data-minimisation measure, never
     * the enforcement point: §13.2.1 requires the node to check consent itself
     * regardless of what happened upstream.
     */
    private NodeResolution consentDenied(final EndpointDescriptor endpoint, final String refHash,
                                         final Instant now, final String reason) {
        if (endpoint.nodeId() != null) {
            final Optional<ResolutionBinding> cached = bindings.findByPatientRefHashAndNodeId(refHash, endpoint.nodeId());
            final Instant expires = now.plus(properties.localization().cacheTtl());
            bindings.save(new ResolutionBinding(
                    cached.map(ResolutionBinding::id).orElse(null),
                    refHash, endpoint.nodeId(),
                    // Deliberately drops any cached ehr_id: a node we are not
                    // permitted to ask must not keep a usable handle to the
                    // patient lying around in our own database.
                    null, ResolutionBinding.CONSENT_DENIED, now, expires));
        }
        return new NodeResolution(endpoint.endpointId(), null,
                NodeResolution.Status.CONSENT_DENIED, reason);
    }

    /** Phrased as a where-answer, never as a permission decision (§14.4). */
    private static String localizationReason(final LocalizationResult result) {
        return result.outcome() == LocalizationResult.Outcome.NO_RECORDS
                ? "localization found no records for this patient at any member node"
                : "localization did not name this node as holding records for this patient";
    }

    private static String rootMessage(final Throwable e) {
        final Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    // ---- cross-reference ----------------------------------------------------

    private NodeResolution resolveOne(final PatientToken token, final EndpointDescriptor endpoint,
                                      final String refHash, final Instant now) {
        final Optional<ResolutionBinding> cached = endpoint.nodeId() == null
                ? Optional.empty()
                : bindings.findByPatientRefHashAndNodeId(refHash, endpoint.nodeId());

        if (cached.isPresent() && !cached.get().isExpired(now)) {
            final ResolutionBinding binding = cached.get();
            if (ResolutionBinding.CONSENT_DENIED.equals(binding.consentStatus())) {
                return new NodeResolution(endpoint.endpointId(), null,
                        NodeResolution.Status.CONSENT_DENIED, "cached consent denial");
            }
            if (binding.localEhrId() != null) {
                return new NodeResolution(endpoint.endpointId(), binding.localEhrId(),
                        NodeResolution.Status.RESOLVED);
            }
            return new NodeResolution(endpoint.endpointId(), null, NodeResolution.Status.NOT_RESOLVED);
        }

        final Optional<String> resolved = crossReference.resolveEhrId(token, endpoint);
        if (endpoint.nodeId() != null) {
            final Instant expires = now.plus(properties.identity().bindingTtl());
            final ResolutionBinding fresh = new ResolutionBinding(
                    cached.map(ResolutionBinding::id).orElse(null),
                    // Never 'permitted'. Resolving a patient at a node says
                    // where the data is, not that it may be released — §14.4
                    // forbids inferring consent from a localization hit, and a
                    // cross-reference hit says even less. Only a consent
                    // authority's actual decision may write this column.
                    refHash, endpoint.nodeId(), resolved.orElse(null),
                    ResolutionBinding.CONSENT_UNKNOWN, now, expires);
            bindings.save(fresh);
        }
        return resolved
                .map(ehrId -> new NodeResolution(endpoint.endpointId(), ehrId, NodeResolution.Status.RESOLVED))
                .orElseGet(() -> new NodeResolution(endpoint.endpointId(), null,
                        NodeResolution.Status.NOT_RESOLVED));
    }

}
