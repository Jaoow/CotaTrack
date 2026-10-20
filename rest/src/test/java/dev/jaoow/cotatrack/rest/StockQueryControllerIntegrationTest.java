package dev.jaoow.cotatrack.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class StockQueryControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/forex/USD/BRL should succeed and return FX quote")
    void testForexSinglePairWithSlash() throws Exception {
        mockMvc.perform(get("/api/forex/USD/BRL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].symbol", is("USD/BRL")))
                .andExpect(jsonPath("$.results[0].regularMarketPrice", notNullValue()));
    }

    @Test
    @DisplayName("GET /api/forex/USD/BRL,EUR/BRL should succeed and return multiple FX quotes")
    void testForexMultiplePairsWithSlashes() throws Exception {
        mockMvc.perform(get("/api/forex/USD/BRL,EUR/BRL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(2)))
                .andExpect(jsonPath("$.results[*].symbol", containsInAnyOrder("USD/BRL", "EUR/BRL")));
    }

    @Test
    @DisplayName("GET /api/forex/XYZ/ABC should return 400 Bad Request")
    void testForexUnsupportedPairReturns400() throws Exception {
        mockMvc.perform(get("/api/forex/XYZ/ABC"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is(true)))
                .andExpect(jsonPath("$.message", notNullValue()));
    }

    @Test
    @DisplayName("GET /api/quote/PETR4?dividends=true should include dividends in response")
    void testStockQuoteWithDividendsParam() throws Exception {
        mockMvc.perform(get("/api/quote/PETR4").param("dividends", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].dividends", notNullValue()))
                .andExpect(jsonPath("$.results[0].dividends.cashDividends", notNullValue()));
    }

    @Test
    @DisplayName("GET /api/quote/PETR4?includeDividends=true should also include dividends in response")
    void testStockQuoteWithIncludeDividendsParam() throws Exception {
        mockMvc.perform(get("/api/quote/PETR4").param("includeDividends", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].dividends", notNullValue()))
                .andExpect(jsonPath("$.results[0].dividends.cashDividends", notNullValue()));
    }

    @Test
    @DisplayName("GET /api/quote/PETR4 without params should return 200")
    void testStockQuoteWithoutParams() throws Exception {
        mockMvc.perform(get("/api/quote/PETR4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].symbol", notNullValue()));
    }
}
