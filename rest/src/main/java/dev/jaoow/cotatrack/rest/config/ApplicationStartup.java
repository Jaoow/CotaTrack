package dev.jaoow.cotatrack.rest.config;

import dev.jaoow.cotatrack.api.yahoo.YahooCredentials;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ApplicationStartup {

    @EventListener(ContextRefreshedEvent.class)
    public void onApplicationEvent() {
        YahooCredentials.fetch();

        // Set the default logo url
        // TODO: Change this to a custom logo
        System.setProperty("defaultLogoUrl", "");
    }
}
