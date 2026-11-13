package com.example.orders.tenant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.orders.common.error.ProblemTypes;

/** Runs against the Flyway-seeded partners: partner-a (active) and partner-suspended (inactive). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PartnerAuthenticationTest.ProbeController.class)
class PartnerAuthenticationTest {

    @RestController
    static class ProbeController {

        @GetMapping("/orders/probe")
        String whoAmI(PartnerContext partner) {
            return partner.partnerId();
        }

        @GetMapping("/partners/{partnerId}/probe")
        String whoAmIOnPartnerPath(PartnerContext partner) {
            return partner.partnerId();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void activePartnerIsResolvedIntoPartnerContext() throws Exception {
        mockMvc.perform(get("/orders/probe").header(PartnerAuthenticationInterceptor.PARTNER_HEADER, "partner-a"))
                .andExpect(status().isOk())
                .andExpect(content().string("partner-a"));
    }

    @Test
    void missingPartnerHeaderIsUnauthenticated() throws Exception {
        expectUnauthenticated(get("/orders/probe"));
    }

    @Test
    void blankPartnerHeaderIsUnauthenticated() throws Exception {
        expectUnauthenticated(get("/orders/probe").header(PartnerAuthenticationInterceptor.PARTNER_HEADER, "  "));
    }

    @Test
    void unknownPartnerIsUnauthenticated() throws Exception {
        expectUnauthenticated(get("/orders/probe").header(PartnerAuthenticationInterceptor.PARTNER_HEADER, "nobody"));
    }

    @Test
    void inactivePartnerIsUnauthenticated() throws Exception {
        expectUnauthenticated(
                get("/orders/probe").header(PartnerAuthenticationInterceptor.PARTNER_HEADER, "partner-suspended"));
    }

    @Test
    void matchingPathPartnerIsAllowed() throws Exception {
        mockMvc.perform(get("/partners/partner-a/probe").header(PartnerAuthenticationInterceptor.PARTNER_HEADER, "partner-a"))
                .andExpect(status().isOk())
                .andExpect(content().string("partner-a"));
    }

    @Test
    void differentPathPartnerIsForbidden() throws Exception {
        mockMvc.perform(get("/partners/partner-b/probe").header(PartnerAuthenticationInterceptor.PARTNER_HEADER, "partner-a"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.PARTNER_MISMATCH.toString()))
                .andExpect(jsonPath("$.title").value("Partner mismatch"));
    }

    @Test
    void authenticationIsCheckedBeforePathPartner() throws Exception {
        expectUnauthenticated(get("/partners/partner-b/probe"));
    }

    private void expectUnauthenticated(RequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.UNAUTHENTICATED.toString()))
                .andExpect(jsonPath("$.title").value("Unauthenticated"))
                .andExpect(jsonPath("$.status").value(401));
    }
}
