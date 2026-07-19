package com.openaiapi.api;

import com.openaiapi.service.CursorSessionService;
import com.openaiapi.service.UpstreamProxyService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/cursor/sessions")
public class CursorSessionController {

    private final CursorSessionService cursorSessionService;
    private final UpstreamProxyService upstreamProxyService;

    public CursorSessionController(
            CursorSessionService cursorSessionService, UpstreamProxyService upstreamProxyService) {
        this.cursorSessionService = cursorSessionService;
        this.upstreamProxyService = upstreamProxyService;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam(required = false) String model) {
        Map<String, Object> local =
                model != null && !model.isBlank()
                        ? cursorSessionService.resetModel(model)
                        : cursorSessionService.resetAll();

        Map<String, Object> result = new HashMap<>(local);
        if (upstreamProxyService.isEnabled()) {
            try {
                Map<String, Object> upstream = upstreamProxyService.proxyUiReset();
                result.put("upstream", upstream);
            } catch (Exception ex) {
                result.put("upstream_error", ex.getMessage() == null ? "upstream reset failed" : ex.getMessage());
            }
        }
        return result;
    }
}
