// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates this gateway's own responses against the JSON Schemas published by
 * the specification (§9.1 and §7a.2, {@code modules/ROOT/attachments/}).
 *
 * <p>This is the cheapest conformance check available to a reference
 * implementation, and the one most worth having here: the schemas <em>are</em>
 * the wire contract, so asserting against them directly beats re-transcribing
 * that contract into hand-written assertions that can quietly drift from it. It
 * is also the check that would have caught the defect this class was added
 * alongside — the envelope serialising {@code rows} as objects while the spec
 * claimed ITS-REST conformance (finding F3).
 *
 * <p>The schema files under {@code src/test/resources/spec-schemas/} are
 * <strong>copies</strong> of the published attachments. Refresh them when
 * re-binding to a new spec release; they are vendored rather than fetched so the
 * test suite neither needs network access nor silently changes meaning when the
 * specification site is republished.
 */
final class SpecSchemaValidator {

    static final String RESULT_SET = "federated-result-set.schema.json";
    static final String OPTIONS_ROOT = "options-root.schema.json";

    private SpecSchemaValidator() {
    }

    /**
     * Asserts {@code instance} validates against the named published schema,
     * failing with every violation rather than only the first — a response that
     * is wrong in three places is more useful to see whole.
     */
    static void assertValid(JsonNode instance, String schemaName) {
        assertThat(instance).as("no response body to validate against " + schemaName).isNotNull();

        Set<ValidationMessage> errors = schema(schemaName).validate(instance);

        assertThat(errors)
                .as("%s must validate against the published %s:%n%s%n%nInstance:%n%s",
                        "the gateway's response", schemaName,
                        errors.stream().map(ValidationMessage::getMessage)
                                .collect(Collectors.joining("\n  - ", "  - ", "")),
                        instance.toPrettyString())
                .isEmpty();
    }

    private static JsonSchema schema(String schemaName) {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                // The schemas set `format` on a few string members (uri, date-time).
                // Assert them: a gateway emitting a malformed `url` or `_created` is
                // a defect worth failing on, and these are our own responses, not
                // third-party input we must be lenient with.
                .formatAssertionsEnabled(true)
                .build();

        try (InputStream in = SpecSchemaValidator.class.getClassLoader()
                .getResourceAsStream("spec-schemas/" + schemaName)) {
            if (in == null) {
                throw new IllegalStateException(
                        "spec-schemas/" + schemaName + " is not on the test classpath; "
                                + "copy it from the specification's modules/ROOT/attachments/");
            }
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(in, config);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read spec-schemas/" + schemaName, e);
        }
    }
}
