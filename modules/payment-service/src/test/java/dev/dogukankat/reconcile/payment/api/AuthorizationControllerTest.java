package dev.dogukankat.reconcile.payment.api;

import dev.dogukankat.reconcile.payment.application.AuthorizationService;
import dev.dogukankat.reconcile.payment.application.ServiceResult;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthorizationController.class)
@Import({IdempotencyHashing.class, GlobalExceptionHandler.class})
class AuthorizationControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean AuthorizationService service;

    private static final String VALID_BODY = """
            {
              "merchantId": "00000000-0000-0000-0000-000000000001",
              "amount": "100.00",
              "currency": "USD",
              "expiresAt": "2026-05-19T10:00:00Z"
            }
            """;

    @Test
    void createdResultMapsTo201WithLocationHeader() throws Exception {
        UUID resourceId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(service.authorize(any()))
                .thenReturn(new ServiceResult.Created("{\"id\":\"...\"}", resourceId));

        mockMvc.perform(post("/authorizations")
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/authorizations/" + resourceId))
                .andExpect(content().json("{\"id\":\"...\"}"));
    }

    @Test
    void replayedResultEchoesCachedBodyAndStatus() throws Exception {
        UUID resourceId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(service.authorize(any()))
                .thenReturn(new ServiceResult.Replayed(201, "{\"cached\":true}", resourceId));

        mockMvc.perform(post("/authorizations")
                        .header("Idempotency-Key", "k-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(content().json("{\"cached\":true}"));
    }

    @Test
    void hashMismatchMapsTo409() throws Exception {
        when(service.authorize(any()))
                .thenReturn(new ServiceResult.IdempotencyHashMismatch());

        mockMvc.perform(post("/authorizations")
                        .header("Idempotency-Key", "k-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(content().json(
                        "{\"error\":\"idempotency_key_reuse_with_different_parameters\"}"));
    }

    @Test
    void missingIdempotencyHeaderReturns400() throws Exception {
        mockMvc.perform(post("/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"missing_required_header\"}"));
    }

    @Test
    void invalidBodyReturns400() throws Exception {
        String missingAmount = """
                {
                  "merchantId": "00000000-0000-0000-0000-000000000001",
                  "currency": "USD",
                  "expiresAt": "2026-05-19T10:00:00Z"
                }
                """;

        mockMvc.perform(post("/authorizations")
                        .header("Idempotency-Key", "k-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingAmount))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"invalid_request\"}"));
    }
}
