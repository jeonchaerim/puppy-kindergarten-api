package com.puppykindergarten.config;

import java.time.Clock;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationPropertiesScan(basePackages = "com.puppykindergarten")
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
