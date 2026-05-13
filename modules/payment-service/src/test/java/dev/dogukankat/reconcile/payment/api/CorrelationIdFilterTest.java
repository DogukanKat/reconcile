package dev.dogukankat.reconcile.payment.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void inboundHeaderIsBoundToMdcDuringRequestAndClearedAfter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

        filter.doFilter(request, response, capture(mdcDuringRequest));

        assertThat(mdcDuringRequest.get()).isEqualTo("abc-123");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void missingHeaderGeneratesUuidAndEchoesItOnResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

        filter.doFilter(request, response, capture(mdcDuringRequest));

        String generated = mdcDuringRequest.get();
        assertThat(generated).isNotNull();
        // valid UUID
        UUID.fromString(generated);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(generated);
    }

    @Test
    void blankHeaderIsTreatedAsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "  ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

        filter.doFilter(request, response, capture(mdcDuringRequest));

        String generated = mdcDuringRequest.get();
        assertThat(generated).isNotBlank();
        UUID.fromString(generated);
    }

    @Test
    void inboundHeaderIsEchoedOnResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("abc-123");
    }

    @Test
    void mdcIsClearedEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain blowUp = (req, res) -> {
            throw new ServletException("boom");
        };

        try {
            filter.doFilter(request, response, blowUp);
        } catch (Exception ignored) {
            // expected
        }

        assertThat(MDC.get("correlationId")).isNull();
    }

    private static FilterChain capture(AtomicReference<String> sink) {
        return new FilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res)
                    throws IOException, ServletException {
                sink.set(MDC.get("correlationId"));
            }
        };
    }
}
