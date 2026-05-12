package dev.dogukankat.reconcile.payment.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 over a canonical JSON form of the request, per ADR-0006:
 * keys alphabetical, no whitespace, nulls excluded so optional fields
 * don't perturb the hash. Excluded-field policy stays per-endpoint
 * (callers serialize whichever subset they want hashed).
 */
@Component
public class IdempotencyHashing {

    private final ObjectMapper canonical;

    public IdempotencyHashing() {
        this.canonical = JsonMapper.builder()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
        this.canonical.registerModule(new JavaTimeModule());
    }

    public String compute(Object request) {
        String json;
        try {
            json = canonical.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize request for hashing", e);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in JVM", e);
        }
    }
}
