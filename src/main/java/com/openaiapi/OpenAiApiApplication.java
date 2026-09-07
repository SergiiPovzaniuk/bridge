package com.openaiapi;

import com.openaiapi.config.RelayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(RelayProperties.class)
@EnableScheduling
public class OpenAiApiApplication {

    public static void main(String[] args) {
        EnvUtil.setEnv("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        SpringApplication.run(OpenAiApiApplication.class, args);
    }
}
