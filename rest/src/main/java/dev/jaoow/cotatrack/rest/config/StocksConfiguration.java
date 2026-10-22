package dev.jaoow.cotatrack.rest.config;

import dev.jaoow.cotatrack.api.cache.BatchQuoteCoalescer;
import dev.jaoow.cotatrack.api.cache.CotaTrackCacheManager;
import dev.jaoow.cotatrack.api.classification.AssetMarket;
import dev.jaoow.cotatrack.api.provider.impl.TwelveDataQuoteProvider;
import dev.jaoow.cotatrack.api.provider.orchestrator.ProviderOrchestrator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class StocksConfiguration {
    @Bean
    public ProviderOrchestrator usProviderOrchestrator(CotaTrackCacheManager cacheManager, Environment environment) {
        Duration timeout = Duration.parse(environment.getProperty("cotatrack.stocks.timeout", "PT3S"));
        Map<String, Integer> costs = new HashMap<>();
        TwelveDataQuoteProvider.DEFAULT_CREDITS.forEach((endpoint, defaultCost) ->
                costs.put(endpoint, environment.getProperty("cotatrack.stocks.credits." + endpoint,
                        Integer.class, defaultCost)));
        TwelveDataQuoteProvider provider = new TwelveDataQuoteProvider(
                HttpClient.newBuilder().connectTimeout(timeout).build(),
                environment.getProperty("cotatrack.stocks.api-key", ""),
                environment.getProperty("cotatrack.stocks.enabled", Boolean.class, false),
                cacheManager, URI.create("https://api.twelvedata.com/"), timeout,
                environment.getProperty("cotatrack.stocks.credits-per-minute", Integer.class, 8),
                environment.getProperty("cotatrack.stocks.credits-per-day", Integer.class, 800),
                environment.getProperty("cotatrack.stocks.fundamentals-enabled", Boolean.class, false), costs);
        return new ProviderOrchestrator(AssetMarket.US, List.of(provider), cacheManager, new BatchQuoteCoalescer());
    }
}
