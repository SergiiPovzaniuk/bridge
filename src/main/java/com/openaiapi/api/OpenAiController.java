package com.openaiapi.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openaiapi.api.dto.ChatCompletionRequest;
import com.openaiapi.api.dto.ModelListResponse;
import com.openaiapi.api.dto.ModelObject;
import com.openaiapi.service.ChatCompletionService;
import com.openaiapi.service.CursorSessionService;
import com.openaiapi.service.ModelService;
import com.openaiapi.service.StreamAdapterService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class OpenAiController {

    private static final long HEARTBEAT_SECONDS = 15;

    private final ChatCompletionService chatCompletionService;
    private final ModelService modelService;
    private final StreamAdapterService streamAdapterService;
    private final ObjectMapper objectMapper;
    private final CursorSessionService cursorSessionService;

    public OpenAiController(
            ChatCompletionService chatCompletionService,
            ModelService modelService,
            StreamAdapterService streamAdapterService,
            ObjectMapper objectMapper,
            CursorSessionService cursorSessionService) {
        this.chatCompletionService = chatCompletionService;
        this.modelService = modelService;
        this.streamAdapterService = streamAdapterService;
        this.objectMapper = objectMapper;
        this.cursorSessionService = cursorSessionService;
    }

    @GetMapping("/models")
    public ModelListResponse listModels() {
        return modelService.list();
    }

    @GetMapping("/models/{id}")
    public ModelObject getModel(@PathVariable String id) {
        return modelService.get(id);
    }

    @PostMapping(
            value = "/chat/completions",
            produces = {MediaType.APPLICATION_JSON_VALUE, "text/event-stream"})
    public void chatCompletions(@RequestBody ChatCompletionRequest request, HttpServletResponse response)
            throws IOException {
        boolean streamToClient = request.isStream();
        request.setStream(false);

        if (streamToClient) {
            writeStreamingCompletion(request, response);
            return;
        }

        JsonNode completion = chatCompletionService.completeJson(request);
        cursorSessionService.recordFromResponse(completion);
        applyCursorHeaders(response, completion);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), completion);
    }

    private void writeStreamingCompletion(ChatCompletionRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/event-stream");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        Writer writer = response.getWriter();
        writer.write(": connected\n\n");
        writer.flush();

        CompletableFuture<JsonNode> future =
                CompletableFuture.supplyAsync(() -> chatCompletionService.completeJson(request));
        try {
            while (true) {
                try {
                    JsonNode completion = future.get(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                    cursorSessionService.recordFromResponse(completion);
                    applyCursorHeaders(response, completion);
                    streamAdapterService.writeAsSse(completion, writer);
                    return;
                } catch (TimeoutException ignored) {
                    writer.write(": keepalive\n\n");
                    writer.flush();
                }
            }
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for completion", ex);
        }
    }

    private void applyCursorHeaders(HttpServletResponse response, JsonNode completion) {
        JsonNode cursor = completion.path("cursor");
        if (cursor.isMissingNode() || cursor.isNull()) {
            return;
        }
        String agentId = cursor.path("agentId").asText(null);
        String runId = cursor.path("runId").asText(null);
        if (agentId != null && !agentId.isBlank()) {
            response.setHeader("x-cursor-agent-id", agentId);
        }
        if (runId != null && !runId.isBlank()) {
            response.setHeader("x-cursor-run-id", runId);
        }
    }
}
