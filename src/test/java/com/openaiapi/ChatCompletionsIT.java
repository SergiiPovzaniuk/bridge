package com.openaiapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.upstream.enabled=false",
    "app.upstream.use-playwright=false"
})
class ChatCompletionsIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void health() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void listModels() throws Exception {
        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data[0].id").value("dummy-gpt"));
    }

    @Test
    void textCompletionWithoutTools() throws Exception {
        String body = """
                {
                  "model": "dummy-gpt",
                  "messages": [{"role": "user", "content": "hello"}]
                }
                """;
        mockMvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
                .andExpect(jsonPath("$.choices[0].message.content").value("Dummy reply to: hello"));
    }

    @Test
    void toolCallsWhenToolsPresent() throws Exception {
        String body = """
                {
                  "model": "dummy-tools",
                  "messages": [{"role": "user", "content": "read the readme"}],
                  "tools": [{
                    "type": "function",
                    "function": {
                      "name": "read_file",
                      "description": "Read a file",
                      "parameters": {
                        "type": "object",
                        "properties": {"filePath": {"type": "string"}},
                        "required": ["filePath"]
                      }
                    }
                  }]
                }
                """;
        MvcResult toolResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].finish_reason").value("tool_calls"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.name").value("read_file"))
                .andReturn();
        String content = toolResult.getResponse().getContentAsString();
        assertThat(content).contains("filePath").contains("README.md");
    }

    @Test
    void createFileToolCall() throws Exception {
        String body = """
                {
                  "model": "dummy-tools",
                  "messages": [{"role": "user", "content": "create a new file hello.txt"}],
                  "tools": [{
                    "type": "function",
                    "function": {
                      "name": "create_file",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "filePath": {"type": "string"},
                          "content": {"type": "string"}
                        },
                        "required": ["filePath", "content"]
                      }
                    }
                  }]
                }
                """;
        MvcResult created = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].finish_reason").value("tool_calls"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.name").value("create_file"))
                .andReturn();
        assertThat(created.getResponse().getContentAsString()).contains("hello.txt");
    }

    @Test
    void createUsesWorkspaceFromOpenFiles() throws Exception {
        String body = """
                {
                  "model": "dummy-tools",
                  "messages": [
                    {"role": "system", "content": "Open file: C:\\\\Users\\\\sergi\\\\projects\\\\other_app\\\\src\\\\main.ts"},
                    {"role": "user", "content": "create a new file notes.md"}
                  ],
                  "tools": [{
                    "type": "function",
                    "function": {
                      "name": "create_file",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "filePath": {"type": "string"},
                          "content": {"type": "string"}
                        },
                        "required": ["filePath", "content"]
                      }
                    }
                  }]
                }
                """;
        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].finish_reason").value("tool_calls"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.name").value("create_file"))
                .andReturn();
        String content = result.getResponse().getContentAsString();
        assertThat(content).contains("other_app").contains("notes.md");
        assertThat(content).doesNotContain("open_ai_api");
    }

    @Test
    void createUsesRemoteLinuxWorkspace() throws Exception {
        String body = """
                {
                  "model": "dummy-tools",
                  "messages": [
                    {"role": "system", "content": "Open file: /home/dev/remote_app/src/App.tsx"},
                    {"role": "user", "content": "create a new file hello.txt"}
                  ],
                  "tools": [{
                    "type": "function",
                    "function": {
                      "name": "create_file",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "filePath": {"type": "string"},
                          "content": {"type": "string"}
                        },
                        "required": ["filePath", "content"]
                      }
                    }
                  }]
                }
                """;
        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].finish_reason").value("tool_calls"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .contains("/home/dev/remote_app/hello.txt")
                .doesNotContain("open_ai_api");
    }

    @Test
    void editAfterCreateAlreadyExists() throws Exception {
        String body = """
                {
                  "model": "dummy-tools",
                  "messages": [
                    {"role": "user", "content": "create a new file hello.txt"},
                    {
                      "role": "assistant",
                      "tool_calls": [{
                        "id": "call_1",
                        "type": "function",
                        "function": {
                          "name": "create_file",
                          "arguments": "{\\"filePath\\":\\"C:\\\\\\\\Users\\\\\\\\sergi\\\\\\\\projects\\\\\\\\other_app\\\\\\\\hello.txt\\",\\"content\\":\\"hi\\"}"
                        }
                      }]
                    },
                    {
                      "role": "tool",
                      "tool_call_id": "call_1",
                      "content": "ERROR while calling tool: File already exists. You must use an edit tool to modify it."
                    }
                  ],
                  "tools": [
                    {"type":"function","function":{"name":"create_file","parameters":{"type":"object","required":["filePath","content"],"properties":{"filePath":{"type":"string"},"content":{"type":"string"}}}}},
                    {"type":"function","function":{"name":"replace_string_in_file","parameters":{"type":"object","required":["filePath","oldString","newString"],"properties":{"filePath":{"type":"string"},"oldString":{"type":"string"},"newString":{"type":"string"}}}}}
                  ]
                }
                """;
        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].finish_reason").value("tool_calls"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.name")
                        .value("replace_string_in_file"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("other_app").contains("hello.txt");
    }

    @Test
    void missingFileRetriesCreateInWorkspace() throws Exception {
        String body = """
                {
                  "model": "dummy-tools",
                  "messages": [
                    {"role": "system", "content": "Open file: /home/dev/remote_app/src/App.tsx"},
                    {"role": "user", "content": "read README.md"},
                    {
                      "role": "assistant",
                      "tool_calls": [{
                        "id": "call_1",
                        "type": "function",
                        "function": {
                          "name": "read_file",
                          "arguments": "{\\"filePath\\":\\"c:\\\\\\\\README.md\\"}"
                        }
                      }]
                    },
                    {
                      "role": "tool",
                      "tool_call_id": "call_1",
                      "content": "File does not exist: c:\\\\README.md. Use the create_file tool to create it, or correct your filepath."
                    }
                  ],
                  "tools": [
                    {"type":"function","function":{"name":"create_file","parameters":{"type":"object","required":["filePath","content"],"properties":{"filePath":{"type":"string"},"content":{"type":"string"}}}}},
                    {"type":"function","function":{"name":"read_file","parameters":{"type":"object","required":["filePath"],"properties":{"filePath":{"type":"string"}}}}}
                  ]
                }
                """;
        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].finish_reason").value("tool_calls"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.name").value("create_file"))
                .andReturn();
        String content = result.getResponse().getContentAsString();
        assertThat(content).contains("/home/dev/remote_app/").contains("README.md");
        assertThat(content).doesNotContain("Tool completed successfully");
        assertThat(content).doesNotContain("c:\\\\README.md");
    }

    @Test
    void editAndTerminalToolSelection() throws Exception {
        String edit = """
                {
                  "model": "dummy-tools",
                  "messages": [{"role": "user", "content": "edit and replace text in README"}],
                  "tools": [
                    {"type":"function","function":{"name":"create_file","parameters":{"type":"object","required":["filePath","content"],"properties":{"filePath":{"type":"string"},"content":{"type":"string"}}}}},
                    {"type":"function","function":{"name":"replace_string_in_file","parameters":{"type":"object","required":["filePath","oldString","newString"],"properties":{"filePath":{"type":"string"},"oldString":{"type":"string"},"newString":{"type":"string"}}}}},
                    {"type":"function","function":{"name":"run_in_terminal","parameters":{"type":"object","required":["command"],"properties":{"command":{"type":"string"}}}}}
                  ]
                }
                """;
        mockMvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].finish_reason").value("tool_calls"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.name")
                        .value("replace_string_in_file"));

        String terminal = """
                {
                  "model": "dummy-tools",
                  "messages": [{"role": "user", "content": "run the command echo hi in terminal"}],
                  "tools": [
                    {"type":"function","function":{"name":"create_file","parameters":{"type":"object","required":["filePath","content"],"properties":{"filePath":{"type":"string"},"content":{"type":"string"}}}}},
                    {"type":"function","function":{"name":"run_in_terminal","parameters":{"type":"object","required":["command"],"properties":{"command":{"type":"string"}}}}}
                  ]
                }
                """;
        mockMvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(terminal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].finish_reason").value("tool_calls"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.name").value("run_in_terminal"));
    }

    @Test
    void finalAnswerAfterToolResult() throws Exception {
        String body = """
                {
                  "model": "dummy-gpt",
                  "messages": [
                    {"role": "user", "content": "read the readme"},
                    {
                      "role": "assistant",
                      "tool_calls": [{
                        "id": "call_1",
                        "type": "function",
                        "function": {"name": "read_file", "arguments": "{\\"path\\":\\"README.md\\"}"}
                      }]
                    },
                    {"role": "tool", "tool_call_id": "call_1", "content": "# OpenAI Dummy"}
                  ],
                  "tools": [{
                    "type": "function",
                    "function": {"name": "read_file", "parameters": {"type": "object"}}
                  }]
                }
                """;
        mockMvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
                .andExpect(jsonPath("$.choices[0].message.content")
                        .value("Tool completed successfully. Result: # OpenAI Dummy"));
    }

    @Test
    void streamRequestReturnsSseForClient() throws Exception {
        String body = """
                {
                  "model": "dummy-fast",
                  "stream": true,
                  "messages": [{"role": "user", "content": "hi"}]
                }
                """;
        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        String response = result.getResponse().getContentAsString();
        assertThat(response).contains("data: ").contains("[DONE]").contains("chat.completion.chunk");
        assertThat(response.trim()).endsWith("data: [DONE]");
    }
}
