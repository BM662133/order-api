package com.example.orders.common.error;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.orders.tenant.PartnerRepository;

@WebMvcTest
@Import(GlobalExceptionHandlerTest.ProbeController.class)
class GlobalExceptionHandlerTest {

    static class TeapotException extends ApiException {
        TeapotException() {
            super(HttpStatus.I_AM_A_TEAPOT, URI.create("https://order-api.example.com/problems/teapot"),
                    "Teapot", "Short and stout.");
        }

        @Override
        public Map<String, Object> properties() {
            return Map.of("spout", "left");
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/test/api-exception")
        void apiException() {
            throw new TeapotException();
        }

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("secret internal detail");
        }
    }

    @MockitoBean
    private PartnerRepository partnerRepository; // required by TenantWebConfig

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiExceptionIsRenderedWithItsStatusTypeTitleAndProperties() throws Exception {
        mockMvc.perform(get("/test/api-exception"))
                .andExpect(status().isIAmATeapot())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://order-api.example.com/problems/teapot"))
                .andExpect(jsonPath("$.title").value("Teapot"))
                .andExpect(jsonPath("$.detail").value("Short and stout."))
                .andExpect(jsonPath("$.spout").value("left"));
    }

    @Test
    void unexpectedExceptionIsA500WithoutLeakingItsMessage() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.INTERNAL_ERROR.toString()))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("secret internal detail"))));
    }

    @Test
    void unknownRouteIsAProblemDetail404() throws Exception {
        mockMvc.perform(get("/test/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }
}
