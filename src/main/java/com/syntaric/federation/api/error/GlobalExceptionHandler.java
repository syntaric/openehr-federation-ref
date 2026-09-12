// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.api.error;

import com.syntaric.federation.registry.RegistryConflictException;
import com.syntaric.federation.registry.RegistryInUseException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * RFC 9457 says an absent {@code type} defaults to {@code about:blank}, which
     * is legal but useless: it makes every error a member of the same anonymous
     * class, so a client can only branch on the numeric status. Every problem this
     * class emits therefore carries a stable, machine-readable URN under this
     * prefix.
     *
     * <p>These URNs are protocol vocabulary: they appear in client error handling
     * and in logs, so they are never translated and never renamed casually.
     */
    private static final String TYPE_PREFIX = "urn:openehr-federation:error:";

    @ExceptionHandler(FederationException.class)
    public ResponseEntity<Object> handleFederation(final FederationException ex, final HttpServletRequest request) {
        final HttpStatus status = ex.code().status();
        if (isAdmin(request)) {
            // The `type` is derived from the code rather than left to default:
            // FED_NOT_FOUND on /admin/requests/{id} and a genuinely absent route
            // are both 404s, and the SPA renders them differently.
            final ProblemDetail problem = problem(status, ex.getMessage(),
                    typeFor(ex.code().name()), ex.code().name());
            ex.details().forEach(problem::setProperty);
            return ResponseEntity.status(status).body(problem);
        }
        return ResponseEntity.status(status)
                .body(OpenEhrErrorBody.of(ex.code(), ex.getMessage(), ex.details()));
    }

    /**
     * A referenced registry row cannot be hard-deleted (plan 3 §A1).
     *
     * <p>The reference counts are part of the response body, not just the prose:
     * the console renders them, and they are what tells the operator whether to
     * clean up or take the {@code status: "excluded"} path the detail points at.
     */
    @ExceptionHandler(RegistryInUseException.class)
    public ResponseEntity<Object> handleRegistryInUse(final RegistryInUseException ex) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                ex.getMessage() + ". Set status to \"excluded\" to take it out of fan-out "
                        + "without discarding its history.");
        problem.setType(URI.create("urn:openehr-federation:error:registry-in-use"));
        problem.setTitle(ex.kind() + " is referenced");
        problem.setProperty("references", ex.references());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /** Duplicate id, or a {@code system_id} already claimed by another node. */
    @ExceptionHandler(RegistryConflictException.class)
    public ResponseEntity<Object> handleRegistryConflict(final RegistryConflictException ex) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("urn:openehr-federation:error:registry-conflict"));
        problem.setTitle("Registry conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Bean-validation failures on the admin DTOs, as RFC 9457 (plan 3 §A3).
     *
     * <p>Field errors are reported per field so the form can attach each message
     * to its own control rather than showing one merged string above the whole
     * form — which is what the add/edit mockup renders under {@code system_id}.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidation(final MethodArgumentNotValidException ex) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "One or more fields are invalid.");
        problem.setType(URI.create("urn:openehr-federation:error:validation"));
        problem.setTitle("Invalid request");
        final Map<String, String> errors = new LinkedHashMap<>();
        for (final FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(),
                    error.getDefaultMessage() != null ? error.getDefaultMessage() : "invalid");
        }
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * A missing static resource is a 404, not a server error.
     *
     * <p>Spring signals it with an exception, which the catch-all below would
     * otherwise turn into a 500 — so a stale bundle reference from a cached
     * {@code index.html} would report the gateway as broken rather than the asset
     * as gone, and would log a stack trace per request.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Object> handleMissingResource(final NoResourceFoundException ex,
                                                        final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.NOT_FOUND;
        if (isAdmin(request)) {
            return ResponseEntity.status(status).body(problem(status,
                    "No such admin endpoint.", TYPE_PREFIX + "not-found", "Not found"));
        }
        return ResponseEntity.status(status).build();
    }

    /**
     * A malformed query parameter — {@code ?from=yesterday}, {@code ?size=big} —
     * is the caller's mistake, so 400 rather than the catch-all's 500 (M5's
     * RFC 9457 pass).
     *
     * <p>Every admin read takes {@code from}/{@code to} as ISO instants and
     * {@code page}/{@code size} as ints, all of them arriving from a hand-editable
     * URL. Without this, a mistyped deep link reported the gateway as broken and
     * logged a stack trace per reload. The offending parameter is named in
     * {@code errors} so the screen can point at the filter that is wrong; the
     * <i>value</i> is not echoed, since it is unvalidated caller input.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(final MethodArgumentTypeMismatchException ex,
                                                     final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.BAD_REQUEST;
        if (!isAdmin(request)) {
            return ResponseEntity.status(status)
                    .body(new OpenEhrErrorBody("BAD_REQUEST", "Invalid parameter", null));
        }
        final ProblemDetail problem = problem(status,
                "The parameter '" + ex.getName() + "' is not in the expected format.",
                TYPE_PREFIX + "invalid-parameter", "Invalid parameter");
        problem.setProperty("errors", Map.of(ex.getName(), "invalid format"));
        return ResponseEntity.status(status).body(problem);
    }

    /** A required query parameter is absent. Same reasoning as the mismatch above. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Object> handleMissingParameter(final MissingServletRequestParameterException ex,
                                                         final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.BAD_REQUEST;
        if (!isAdmin(request)) {
            return ResponseEntity.status(status)
                    .body(new OpenEhrErrorBody("BAD_REQUEST", ex.getMessage(), null));
        }
        final ProblemDetail problem = problem(status,
                "The parameter '" + ex.getParameterName() + "' is required.",
                TYPE_PREFIX + "invalid-parameter", "Missing parameter");
        problem.setProperty("errors", Map.of(ex.getParameterName(), "required"));
        return ResponseEntity.status(status).body(problem);
    }

    /**
     * A body that is not parseable JSON, or does not fit the DTO — 400, not the
     * catch-all's 500.
     *
     * <p>The parser's own message is deliberately not forwarded: it quotes the
     * offending input back, and on this surface that input can be a registry
     * import document.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleUnreadableBody(final HttpMessageNotReadableException ex,
                                                       final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.BAD_REQUEST;
        if (!isAdmin(request)) {
            return ResponseEntity.status(status)
                    .body(new OpenEhrErrorBody("BAD_REQUEST", "Malformed request body", null));
        }
        return ResponseEntity.status(status).body(problem(status,
                "The request body could not be read as JSON of the expected shape.",
                TYPE_PREFIX + "malformed-body", "Malformed request body"));
    }

    /** Right path, wrong verb — 405 with the same problem shape as everything else. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> handleMethodNotSupported(
            final HttpRequestMethodNotSupportedException ex, final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.METHOD_NOT_ALLOWED;
        if (!isAdmin(request)) {
            return ResponseEntity.status(status)
                    .body(new OpenEhrErrorBody("METHOD_NOT_ALLOWED", ex.getMessage(), null));
        }
        return ResponseEntity.status(status).body(problem(status,
                "The method " + ex.getMethod() + " is not supported for this endpoint.",
                TYPE_PREFIX + "method-not-allowed", "Method not allowed"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(final Exception ex, final HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        final HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        if (isAdmin(request)) {
            // The exception's own message never reaches the client: at this point
            // it is unclassified, and a leaked JDBC message is how a table name or
            // a connection string ends up on an operator's screen.
            return ResponseEntity.status(status).body(problem(status,
                    "The gateway failed to handle this request.",
                    TYPE_PREFIX + "internal", "Internal error"));
        }
        return ResponseEntity.status(status)
                .body(new OpenEhrErrorBody("INTERNAL_SERVER_ERROR", "Internal error", null));
    }

    /**
     * The admin surface answers RFC 9457; {@code /v1/**} answers the openEHR error
     * shape (plan 3, architectural constraints). Matching on the path rather than
     * on the controller keeps the two answers together in one place, where the
     * asymmetry is visible.
     */
    private static boolean isAdmin(final HttpServletRequest request) {
        return request.getRequestURI().startsWith("/admin");
    }

    /** Every admin problem carries all four of `type`, `title`, `status`, `detail`. */
    private static ProblemDetail problem(final HttpStatus status, final String detail,
                                         final String type, final String title) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(type));
        problem.setTitle(title);
        return problem;
    }

    /** {@code FED_NOT_FOUND} → {@code urn:openehr-federation:error:fed-not-found}. */
    private static String typeFor(final String code) {
        return TYPE_PREFIX + code.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
