package dev.jaoow.cotatrack.rest.config;

import dev.jaoow.cotatrack.api.cache.CacheConfig;
import dev.jaoow.cotatrack.api.cache.CotaTrackCacheManager;
import dev.jaoow.cotatrack.api.provider.StockQuoteProvider;
import dev.jaoow.cotatrack.api.provider.impl.BrapiQuoteProvider;
import dev.jaoow.cotatrack.api.provider.impl.TradingViewQuoteProvider;
import dev.jaoow.cotatrack.api.provider.impl.YahooChartQuoteProvider;
import dev.jaoow.cotatrack.api.provider.orchestrator.ProviderOrchestrator;
import dev.jaoow.cotatrack.api.provider.orchestrator.ProviderOrchestratorHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

@Slf4j
@Configuration
public class ProviderConfiguration {

    @Value("${cotatrack.providers.order:TRADING_VIEW,YAHOO_CHART,BRAPI}")
    private String providerOrder;

    @Value("${cotatrack.providers.tradingview.enabled:true}")
    private boolean tradingViewEnabled;

    @Value("${cotatrack.providers.yahoochart.enabled:true}")
    private boolean yahooChartEnabled;

    @Value("${cotatrack.providers.brapi.enabled:true}")
    private boolean brapiEnabled;

    @Value("${cotatrack.providers.brapi.token:}")
    private String brapiToken;

    @Value("${cotatrack.cache.quote-intraday-ttl:5m}")
    private String quoteIntradayTtl;

    @Value("${cotatrack.cache.history-daily-ttl:12h}")
    private String historyDailyTtl;

    @Value("${cotatrack.cache.dividends-ttl:24h}")
    private String dividendsTtl;

    @Value("${cotatrack.cache.logo-ttl:7d}")
    private String logoTtl;

    @Value("${cotatrack.cache.stale-ttl:24h}")
    private String staleTtl;

    @Bean
    public CotaTrackCacheManager cotaTrackCacheManager() {
        CacheConfig config = CacheConfig.builder()
                .quoteIntradayTtl(CacheConfig.parseDuration(quoteIntradayTtl, CacheConfig.DEFAULT_QUOTE_INTRADAY_TTL))
                .historyDailyTtl(CacheConfig.parseDuration(historyDailyTtl, CacheConfig.DEFAULT_HISTORY_DAILY_TTL))
                .dividendsTtl(CacheConfig.parseDuration(dividendsTtl, CacheConfig.DEFAULT_DIVIDENDS_TTL))
                .logoTtl(CacheConfig.parseDuration(logoTtl, CacheConfig.DEFAULT_LOGO_TTL))
                .staleTtl(CacheConfig.parseDuration(staleTtl, CacheConfig.DEFAULT_STALE_TTL))
                .build();

        CotaTrackCacheManager manager = new CotaTrackCacheManager(config);
        CotaTrackCacheManager.setInstance(manager);
        log.info("Initialized Spring CotaTrackCacheManager with config: {}", config);
        return manager;
    }

    @Bean
    public ProviderOrchestrator providerOrchestrator(CotaTrackCacheManager cacheManager) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        Map<String, StockQuoteProvider> available = new HashMap<>();
        available.put(TradingViewQuoteProvider.PROVIDER_NAME, new TradingViewQuoteProvider(httpClient, tradingViewEnabled));
        available.put(YahooChartQuoteProvider.PROVIDER_NAME, new YahooChartQuoteProvider(httpClient, yahooChartEnabled));
        available.put(BrapiQuoteProvider.PROVIDER_NAME, new BrapiQuoteProvider(httpClient, brapiToken, brapiEnabled));

        List<StockQuoteProvider> ordered = new ArrayList<>();
        String[] order = providerOrder.split(",");
        for (String name : order) {
            String trimmed = name.trim().toUpperCase();
            StockQuoteProvider p = available.get(trimmed);
            if (p != null) {
                ordered.add(p);
            }
        }

        // Add any missing
        for (StockQuoteProvider p : available.values()) {
            if (!ordered.contains(p)) {
                ordered.add(p);
            }
        }

        log.info("Initialized ProviderOrchestrator with order: {}", ordered.stream().map(StockQuoteProvider::getName).toList());
        ProviderOrchestrator orchestrator = new ProviderOrchestrator(ordered, cacheManager);
        ProviderOrchestratorHolder.setOrchestrator(orchestrator);
        return orchestrator;
    }
}
