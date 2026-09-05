package com.pricepulse.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PricePulseActuatorEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Should expose pricepulse in /actuator discovery links")
    void testActuatorLinksExposePricepulse() throws Exception {
        mockMvc.perform(get("/actuator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("_links.pricepulse.href", notNullValue()))
                .andExpect(jsonPath("_links.pricepulse.href", containsString("/actuator/pricepulse")));
    }

    @Test
    @DisplayName("Should return 200 OK and metrics payload from /actuator/pricepulse")
    void testPricepulseEndpointReturnsMetrics() throws Exception {
        mockMvc.perform(get("/actuator/pricepulse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deltaCachePerformance", notNullValue()))
                .andExpect(jsonPath("$.deltaCachePerformance.targetBenchmarkClaim", is("~85% write reduction")))
                .andExpect(jsonPath("$.trafficManagement.amazonAvailableConcurrencyPermits", is(2)))
                .andExpect(jsonPath("$.trafficManagement.flipkartAvailableConcurrencyPermits", is(2)))
                .andExpect(jsonPath("$.systemArchitecture.threadingModel", containsString("Java 21 Virtual Threads")))
                .andExpect(jsonPath("$.systemArchitecture.carrierPinningSafeguard", containsString("ReentrantLock")))
                .andExpect(jsonPath("$.systemArchitecture.distributedLocking", containsString("Redis SETNX")));
    }
}
