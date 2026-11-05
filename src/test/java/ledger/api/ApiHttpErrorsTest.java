package ledger.api;

import ledger.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiHttpErrorsTest {
    private MockMvc mvc;
    private LedgerService ledger;

    @BeforeEach
    void setUp() {
        ledger = mock(LedgerService.class);
        mvc = MockMvcBuilders.standaloneSetup(new LedgerController(ledger), new MissingResourceController())
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new CorrelationFilter())
                .build();
    }

    @Test
    void unsupportedMethodPreserves405AndAllowHeader() throws Exception {
        mvc.perform(put("/api/v1/accounts").header("X-Correlation-Id", "http-error"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("HTTP_405"))
                .andExpect(jsonPath("$.correlationId").value("http-error"));
        verifyNoInteractions(ledger);
    }

    @Test
    void missingResourcePreserves404WithoutLeakingResourcePath() throws Exception {
        mvc.perform(get("/missing-resource"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("HTTP_404"))
                .andExpect(jsonPath("$.detail").value("Not Found"));
        verifyNoInteractions(ledger);
    }

    @RestController
    static class MissingResourceController {
        @GetMapping("/missing-resource")
        void missing() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "private-resource", "/missing-resource");
        }
    }
}
