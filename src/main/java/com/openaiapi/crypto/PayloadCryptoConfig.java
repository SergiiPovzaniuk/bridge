package com.openaiapi.crypto;

import com.openaiapi.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PayloadCryptoConfig {

    @Bean
    public PayloadCrypto payloadCrypto(AppProperties appProperties) {
        if (!appProperties.getUpstream().isEnabled()) {
            return new PayloadCrypto("disabled-upstream-placeholder");
        }
        String key = appProperties.getUpstream().getTransportKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "app.upstream.transport-key / TRANSPORT_KEY is required when upstream is enabled");
        }
        return new PayloadCrypto(key.trim());
    }
}
