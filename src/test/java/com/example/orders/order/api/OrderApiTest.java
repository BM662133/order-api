package com.example.orders.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import com.example.orders.common.error.ProblemTypes;
import com.example.orders.tenant.PartnerAuthenticationInterceptor;

/** Full stack against H2 with the Flyway seed data (partner-a, partner-b, plans BASIC_5G / LEGACY_3G ...). */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiTest {

    private static final String PARTNER = PartnerAuthenticationInterceptor.PARTNER_HEADER;
    private static final String VALID_BODY = """
            {"msisdn": "+447700900123", "planCode": "BASIC_5G", "simType": "ESIM"}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ResultActions createOrder(String callerPartner, String pathPartner, String body) throws Exception {
        return mockMvc.perform(post("/partners/{partnerId}/orders", pathPartner)
                .header(PARTNER, callerPartner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private MvcResult createValidOrder(String partner) throws Exception {
        return createOrder(partner, partner, VALID_BODY).andExpect(status().isCreated()).andReturn();
    }

    private static String idOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private int orderCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
    }

    // ---------- create + get ----------

    @Test
    void createReturns201WithLocationAndOrderInCreated() throws Exception {
        createOrder("partner-a", "partner-a", VALID_BODY)
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Location", matchesPattern("http://localhost/orders/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.partnerId").value("partner-a"))
                .andExpect(jsonPath("$.msisdn").value("+447700900123"))
                .andExpect(jsonPath("$.planCode").value("BASIC_5G"))
                .andExpect(jsonPath("$.simType").value("ESIM"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void getReturnsExactlyWhatCreateReturned() throws Exception {
        MvcResult created = createValidOrder("partner-a");
        String location = created.getResponse().getHeader("Location");

        mockMvc.perform(get(location).header(PARTNER, "partner-a"))
                .andExpect(status().isOk())
                .andExpect(content().json(created.getResponse().getContentAsString(), true));
    }

    @Test
    void msisdnIsStoredWithoutPlusAndReturnedWithPlus() throws Exception {
        MvcResult created = createOrder("partner-a", "partner-a", """
                {"msisdn": "14155550123", "planCode": "UNLIMITED", "simType": "PHYSICAL"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.msisdn").value("+14155550123"))
                .andReturn();

        String stored = jdbc.queryForObject(
                "SELECT msisdn FROM orders WHERE id = ?", String.class, UUID.fromString(idOf(created)));
        assertThat(stored).isEqualTo("14155550123");
    }

    // ---------- validation ----------

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("msisdn missing", """
                        {"planCode": "BASIC_5G", "simType": "ESIM"}""", "msisdn", "must not be null"),
                Arguments.of("msisdn blank", """
                        {"msisdn": "", "planCode": "BASIC_5G", "simType": "ESIM"}""", "msisdn", "E.164"),
                Arguments.of("msisdn too short", """
                        {"msisdn": "+4477", "planCode": "BASIC_5G", "simType": "ESIM"}""", "msisdn", "E.164"),
                Arguments.of("msisdn too long", """
                        {"msisdn": "+1234567890123456", "planCode": "BASIC_5G", "simType": "ESIM"}""", "msisdn", "E.164"),
                Arguments.of("msisdn has letters", """
                        {"msisdn": "+44abc900123", "planCode": "BASIC_5G", "simType": "ESIM"}""", "msisdn", "E.164"),
                Arguments.of("msisdn leading zero", """
                        {"msisdn": "07700900123", "planCode": "BASIC_5G", "simType": "ESIM"}""", "msisdn", "E.164"),
                Arguments.of("msisdn has spaces", """
                        {"msisdn": "+44 7700 900123", "planCode": "BASIC_5G", "simType": "ESIM"}""", "msisdn", "E.164"),
                Arguments.of("planCode missing", """
                        {"msisdn": "+447700900123", "simType": "ESIM"}""", "planCode", "must not be blank"),
                Arguments.of("planCode blank", """
                        {"msisdn": "+447700900123", "planCode": " ", "simType": "ESIM"}""", "planCode", "must not be blank"),
                Arguments.of("planCode unknown", """
                        {"msisdn": "+447700900123", "planCode": "NOPE", "simType": "ESIM"}""", "planCode", "must be an active plan"),
                Arguments.of("planCode inactive", """
                        {"msisdn": "+447700900123", "planCode": "LEGACY_3G", "simType": "ESIM"}""", "planCode", "must be an active plan"),
                Arguments.of("simType missing", """
                        {"msisdn": "+447700900123", "planCode": "BASIC_5G"}""", "simType", "must not be null"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void invalidFieldIsA400ValidationErrorAndNothingIsSaved(String name, String body, String field, String message)
            throws Exception {
        int before = orderCount();

        createOrder("partner-a", "partner-a", body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.VALIDATION_ERROR.toString()))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("Request has 1 invalid field(s)."))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].message").value(containsString(message)));

        assertThat(orderCount()).isEqualTo(before);
    }

    @Test
    void everyInvalidFieldIsReportedSortedByField() throws Exception {
        createOrder("partner-a", "partner-a", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request has 3 invalid field(s)."))
                .andExpect(jsonPath("$.errors[*].field").value(
                        contains("msisdn", "planCode", "simType")));
    }

    @Test
    void unknownSimTypeIsAMalformedRequestListingAllowedValues() throws Exception {
        createOrder("partner-a", "partner-a", """
                {"msisdn": "+447700900123", "planCode": "BASIC_5G", "simType": "NANO"}""")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.MALFORMED_REQUEST.toString()))
                .andExpect(jsonPath("$.detail").value("Invalid value for 'simType'. Allowed values: PHYSICAL, ESIM."));
    }

    @Test
    void unparseableJsonIsAMalformedRequest() throws Exception {
        createOrder("partner-a", "partner-a", "{not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ProblemTypes.MALFORMED_REQUEST.toString()))
                .andExpect(jsonPath("$.detail").value("Request body is missing or is not valid JSON."));
    }

    @Test
    void nonUuidOrderIdIsAnInvalidParameterWithoutEchoingTheValue() throws Exception {
        mockMvc.perform(get("/orders/not-a-uuid").header(PARTNER, "partner-a"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.INVALID_PARAMETER.toString()))
                .andExpect(jsonPath("$.title").value("Invalid parameter"))
                .andExpect(jsonPath("$.detail").value("Invalid value for 'orderId'; expected UUID."))
                .andExpect(jsonPath("$.parameter").value("orderId"))
                .andExpect(jsonPath("$.detail").value(not(containsString("not-a-uuid"))));
    }

    // ---------- authentication and tenant isolation ----------

    @Test
    void missingPartnerHeaderIsA401() throws Exception {
        mockMvc.perform(post("/partners/partner-a/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pathPartnerMustMatchCaller() throws Exception {
        int before = orderCount();

        createOrder("partner-a", "partner-b", VALID_BODY)
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.PARTNER_MISMATCH.toString()));

        assertThat(orderCount()).isEqualTo(before);
    }

    @Test
    void partnerMismatchWinsOverAnInvalidBody() throws Exception {
        createOrder("partner-a", "partner-b", "{}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(ProblemTypes.PARTNER_MISMATCH.toString()));
    }

    @Test
    void anotherPartnersOrderIsIndistinguishableFromAMissingOne() throws Exception {
        String id = idOf(createValidOrder("partner-a"));
        String randomId = UUID.randomUUID().toString();

        mockMvc.perform(get("/orders/{id}", id).header(PARTNER, "partner-b"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.ORDER_NOT_FOUND.toString()))
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.detail").value("Order " + id + " not found."))
                .andExpect(jsonPath("$.orderId").value(id));

        mockMvc.perform(get("/orders/{id}", randomId).header(PARTNER, "partner-b"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(ProblemTypes.ORDER_NOT_FOUND.toString()))
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.detail").value("Order " + randomId + " not found."))
                .andExpect(jsonPath("$.orderId").value(randomId));
    }

    @Test
    void ownerCanStillReadTheirOrder() throws Exception {
        String id = idOf(createValidOrder("partner-b"));

        mockMvc.perform(get("/orders/{id}", id).header(PARTNER, "partner-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerId").value("partner-b"));
        mockMvc.perform(get("/orders/{id}", id).header(PARTNER, "partner-a"))
                .andExpect(status().isNotFound());
    }
}
