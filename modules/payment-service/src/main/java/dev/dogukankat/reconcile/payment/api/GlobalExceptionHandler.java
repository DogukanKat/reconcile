package dev.dogukankat.reconcile.payment.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the handful of exceptions the controller can surface into
 * stable error envelopes. Bodies are minimal JSON — RFC 7807 problem
 * details are an upgrade for later when more endpoints exist and the
 * shape is worth a contract.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<String> validation(MethodArgumentNotValidException e) {
        return error(400, "invalid_request");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<String> missingHeader(MissingRequestHeaderException e) {
        return error(400, "missing_required_header");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> illegalArg(IllegalArgumentException e) {
        // Money / AuthorizeCommand compact constructors throw this.
        return error(400, "invalid_argument");
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<String> illegalState(IllegalStateException e) {
        // Aggregate invariants throw this; the message is intentionally
        // not echoed to the client to avoid leaking internal phrasing.
        return error(422, "domain_invariant_violation");
    }

    private ResponseEntity<String> error(int status, String code) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"" + code + "\"}");
    }
}
