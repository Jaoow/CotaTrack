package dev.jaoow.cotatrack.rest;

import dev.jaoow.cotatrack.api.classification.AssetMarket;
import dev.jaoow.cotatrack.api.provider.orchestrator.ProviderOrchestrator;
import dev.jaoow.cotatrack.rest.config.ApplicationStartup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "cotatrack.stocks.enabled=false")
@AutoConfigureMockMvc
class StocksApplicationTest {
    @MockBean private ApplicationStartup startup;
    @Autowired private ProviderOrchestrator primary;
    @Autowired @Qualifier("usProviderOrchestrator") private ProviderOrchestrator us;
    @Autowired private MockMvc mvc;

    @Test
    void keepsB3PrimaryAndWiresIndependentDisabledUsFlow() throws Exception {
        assertEquals(AssetMarket.B3, primary.getMarket());
        assertEquals(AssetMarket.US, us.getMarket());
        assertNotSame(primary, us);
        mvc.perform(get("/api/stocks/AAPL")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", is(true)));
        mvc.perform(get("/api/diagnostics")).andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[?(@.name == 'TWELVE_DATA')].status", contains("DISABLED")))
                .andExpect(jsonPath("$.providers[?(@.name == 'TRADING_VIEW')].enabled", contains(true)));
    }
}
