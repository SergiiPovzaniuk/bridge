package com.openaiapi.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openaiapi.config.RelayProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class OpenAiControllerTest {

    @Test
    void forwardsContinueExecutionContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Conversation-Id", "conv-1");
        request.addHeader("X-Continue-Workspace", "C:\\remote\\project");
        request.addHeader("X-Continue-OS", "windows");
        request.addHeader("X-Continue-Shell", "powershell");
        request.addHeader("X-Unrelated", "ignored");
        Map<String, String> headers = new OpenAiController(null, new RelayProperties()).forwardedHeaders(request);
        assertEquals("conv-1", headers.get("x-conversation-id"));
        assertEquals("C:\\remote\\project", headers.get("x-continue-workspace"));
        assertEquals("windows", headers.get("x-continue-os"));
        assertEquals("powershell", headers.get("x-continue-shell"));
        assertEquals(4, headers.size());
    }
}
