package com.openaiapi.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TomcatConfig {

    private static final int MAX_POST_BYTES = 100 * 1024 * 1024;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatLargePostCustomizer() {
        return factory -> factory.addConnectorCustomizers((Connector connector) -> {
            connector.setMaxPostSize(MAX_POST_BYTES);
            connector.setMaxSavePostSize(MAX_POST_BYTES);
        });
    }
}
