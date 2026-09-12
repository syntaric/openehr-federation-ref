// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syntaric.federation.registry.Endpoint;
import com.syntaric.federation.registry.Node;
import com.syntaric.federation.registry.NodeIdentifier;
import com.syntaric.federation.registry.Organisation;
import com.syntaric.federation.registry.RegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * Applies a registry document at startup, so a gateway can be handed a federation
 * without a write API.
 *
 * <p>The document is exactly the shape {@link RegistryService#importRegistry} has
 * always consumed: {@code organisations}, {@code nodes}, {@code endpoints},
 * {@code identifiers}. Applying it is idempotent — upsert per id, nothing is ever
 * deleted, and rows absent from the document are left alone — so this runs on
 * every boot rather than only the first.
 *
 * <h2>Why this fails the boot</h2>
 *
 * <p>A missing file, malformed JSON or a dangling reference stops startup.
 * {@code importRegistry} is {@code @Transactional}, so a referential error rolls
 * back whole and nothing half-applies.
 *
 * <p>Failing fast is right <i>because</i> the import is idempotent: the remedy is
 * always "fix the file and restart", with no partial state to reason about. The
 * alternative — log the error and carry on — starts a gateway with an empty or
 * half-populated federation. That gateway is not visibly broken: it answers
 * queries, returns HTTP 200, and reports zero rows, which is indistinguishable
 * from a patient genuinely having no records.
 *
 * <h2>Why this lives in {@code config}</h2>
 *
 * <p>Not in {@code registry}: that package is a leaf and must not depend outward
 * (enforced by {@code ArchitectureTest.registryStaysALeaf}), whereas this depends
 * inward on it. Not in {@code api} either — nothing may depend on controllers,
 * and there is no controller here to depend on. The payload record below is
 * declared locally for the same reason: it is only a Jackson binding target, and
 * {@code importRegistry} takes domain records directly.
 */
@Component
public class RegistryBootstrapLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RegistryBootstrapLoader.class);

    /**
     * Constructed rather than injected, following {@code NodeClientFactory} and
     * {@code TokenAcquiringAuthProvider}.
     *
     * <p>The container's mapper belongs to the HTTP layer and is a different
     * Jackson major version than the {@code com.fasterxml.jackson} annotations
     * on the registry records — so injecting it does not merely couple this to
     * the web stack's configuration, it does not resolve at all.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RegistryService registry;
    private final Resource bootstrapFile;

    public RegistryBootstrapLoader(final RegistryService registry, final FederationProperties properties) {
        this.registry = registry;
        this.bootstrapFile = properties.registry().bootstrapFile();
    }

    @Override
    public void run(final ApplicationArguments args) throws Exception {
        if (bootstrapFile == null) {
            return;
        }
        if (!bootstrapFile.exists()) {
            throw new IllegalStateException(
                    "federation.registry.bootstrap-file is set to " + describe()
                            + ", but no such resource exists. Remove the property to start "
                            + "with whatever the registry tables already hold.");
        }

        RegistryDocument document;
        try (final InputStream in = bootstrapFile.getInputStream()) {
            document = objectMapper.readValue(in, RegistryDocument.class);
        } catch (final Exception ex) {
            throw new IllegalStateException(
                    "Could not read the registry bootstrap document at " + describe()
                            + ": " + ex.getMessage(), ex);
        }

        final RegistryService.ImportCounts counts = registry.importRegistry(
                document.organisationsOrEmpty(), document.nodesOrEmpty(),
                document.endpointsOrEmpty(), document.identifiersOrEmpty());

        log.info("registry bootstrap: {} created, {} updated from {}",
                counts.created(), counts.updated(), describe());
    }

    private String describe() {
        try {
            return bootstrapFile.getURI().toString();
        } catch (final Exception ex) {
            return bootstrapFile.getDescription();
        }
    }

    /**
     * The bootstrap document. Every list is optional: a document that carries
     * only {@code endpoints} is a legitimate way to re-point an existing
     * federation at new base URLs.
     */
    public record RegistryDocument(
            List<Organisation> organisations,
            List<Node> nodes,
            List<Endpoint> endpoints,
            List<NodeIdentifier> identifiers) {

        List<Organisation> organisationsOrEmpty() {
            return organisations == null ? List.of() : organisations;
        }

        List<Node> nodesOrEmpty() {
            return nodes == null ? List.of() : nodes;
        }

        List<Endpoint> endpointsOrEmpty() {
            return endpoints == null ? List.of() : endpoints;
        }

        List<NodeIdentifier> identifiersOrEmpty() {
            return identifiers == null ? List.of() : identifiers;
        }
    }
}
