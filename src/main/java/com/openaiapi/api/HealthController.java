package com.openaiapi.api;

import com.openaiapi.relay.RelayChannel;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final RelayChannel channel;

    public HealthController(RelayChannel channel) {
        this.channel = channel;
    }

    @GetMapping({"/api/version", "/version"})
    public Map<String, Object> version() {
        return Map.of("version", "1.0.0", "status", "ok");
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "relayReady", channel.isReady());
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, Object>> readyz() {
        boolean ready = channel.isReady();
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("ready", ready));
    }
}
