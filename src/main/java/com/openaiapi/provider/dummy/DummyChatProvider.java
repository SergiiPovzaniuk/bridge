package com.openaiapi.provider.dummy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openaiapi.api.dto.ChatCompletionRequest;
import com.openaiapi.api.dto.ChatCompletionResponse;
import com.openaiapi.api.dto.ChatMessage;
import com.openaiapi.api.dto.ToolCall;
import com.openaiapi.api.dto.ToolDefinition;
import com.openaiapi.config.AppProperties;
import com.openaiapi.provider.ChatCompletionProvider;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DummyChatProvider implements ChatCompletionProvider {

    private static final Logger log = LoggerFactory.getLogger(DummyChatProvider.class);

    private static final Pattern WIN_PATH = Pattern.compile(
            "(?<![\\w])([A-Za-z]:\\\\(?:[^\\\\\\s\\\"'`<>|*?]+\\\\)*[^\\\\\\s\\\"'`<>|*?]+)");
    private static final Pattern UNIX_PATH = Pattern.compile(
            "(?<![\\w:])(/(?:[^\\s\\\"'`<>|*?]+/)+[^\\s\\\"'`<>|*?]+)");
    private static final Pattern FILE_URI = Pattern.compile("file:///+([A-Za-z]:[^\\s\\\"'`<>]+|/[^\\s\\\"'`<>]+)");
    private static final Pattern REQUESTED_FILE = Pattern.compile(
            "(?i)(?:create|write|add|make|touch|new)\\s+(?:an?\\s+)?(?:new\\s+)?(?:file\\s+)?[`'\"]?"
                    + "((?:[A-Za-z]:\\\\|/)?"
                    + "[A-Za-z0-9._\\-]+(?:[/\\\\][A-Za-z0-9._\\-]+)*\\.[A-Za-z0-9._\\-]+)");
    private static final Pattern BARE_FILE = Pattern.compile(
            "(?i)\\b([A-Za-z0-9._\\-]+(?:[/\\\\][A-Za-z0-9._\\-]+)*\\.[A-Za-z0-9._\\-]{1,12})\\b");
    private static final Pattern WORKSPACE_HINT = Pattern.compile(
            "(?i)(?:workspace(?:\\s+folder)?|opened\\s+folder|remote\\s+folder|cwd|working\\s+directory)\\s*[:=]\\s*[`'\"]?"
                    + "([^\\n`'\"<]+)");
    private static final Set<String> SOURCEY = Set.of(
            "src",
            "main",
            "java",
            "test",
            "tests",
            "resources",
            "scala",
            "kotlin",
            "js",
            "ts",
            "lib",
            "app",
            "components",
            "pages",
            "public",
            "assets",
            "hooks",
            "utils",
            "types",
            "bin",
            "obj",
            "target",
            "build",
            "dist",
            "out",
            "node_modules",
            ".git",
            ".vscode",
            ".idea");
    private static final Set<String> PROJECT_PARENTS = Set.of(
            "projects", "repos", "code", "dev", "workspace", "workspaces", "home", "Users", "users", "src");

    private final ObjectMapper objectMapper;
    private final String configuredWorkspace;
    private volatile String lastRemoteWorkspace;

    public DummyChatProvider(ObjectMapper objectMapper, AppProperties appProperties) {
        this.objectMapper = objectMapper;
        String configured = appProperties.getDummy().getWorkspaceRoot();
        this.configuredWorkspace = configured == null || configured.isBlank() ? null : normalizeRemote(configured);
    }

    @Override
    public String id() {
        return "dummy";
    }

    @Override
    public ChatCompletionResponse complete(ChatCompletionRequest request) {
        String model = request.getModel() == null ? "dummy-gpt" : request.getModel();
        String finishReason;
        ChatCompletionResponse.ResponseMessage message = new ChatCompletionResponse.ResponseMessage();
        message.setRole("assistant");

        if (shouldCallTool(request)) {
            ToolDefinition tool = selectTool(request);
            ToolCall call = buildToolCall(tool, request);
            message.setContent(null);
            message.setToolCalls(List.of(call));
            finishReason = "tool_calls";
        } else {
            message.setContent(buildTextReply(request));
            finishReason = "stop";
        }

        ChatCompletionResponse.Choice choice = new ChatCompletionResponse.Choice();
        choice.setIndex(0);
        choice.setMessage(message);
        choice.setFinishReason(finishReason);

        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setId("chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        response.setCreated(System.currentTimeMillis() / 1000);
        response.setModel(model);
        response.setChoices(List.of(choice));
        response.setUsage(new ChatCompletionResponse.Usage(16, 32));
        return response;
    }

    private boolean shouldCallTool(ChatCompletionRequest request) {
        List<ToolDefinition> tools = request.getTools();
        if (tools == null || tools.isEmpty()) {
            return false;
        }
        if (isToolChoiceNone(request.getToolChoice())) {
            return false;
        }
        if (lastRoleIsTool(request.getMessages())) {
            return needsToolRetry(request.getMessages());
        }
        return true;
    }

    private boolean isToolChoiceNone(JsonNode toolChoice) {
        return toolChoice != null && toolChoice.isTextual() && "none".equalsIgnoreCase(toolChoice.asText());
    }

    private boolean lastRoleIsTool(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        String role = messages.getLast().getRole();
        return role != null && "tool".equalsIgnoreCase(role);
    }

    private String lastToolContent(List<ChatMessage> messages) {
        return extractContent(messages.getLast()).toLowerCase(Locale.ROOT);
    }

    private boolean needsToolRetry(List<ChatMessage> messages) {
        return needsEditAfterCreateFailure(messages) || needsCreateAfterMissingFile(messages);
    }

    private boolean needsEditAfterCreateFailure(List<ChatMessage> messages) {
        String toolContent = lastToolContent(messages);
        return toolContent.contains("already exists") || toolContent.contains("use an edit tool");
    }

    private boolean needsCreateAfterMissingFile(List<ChatMessage> messages) {
        String toolContent = lastToolContent(messages);
        return toolContent.contains("does not exist")
                || toolContent.contains("file not found")
                || toolContent.contains("use the create_file")
                || toolContent.contains("correct your filepath");
    }

    private ToolDefinition selectTool(ChatCompletionRequest request) {
        if (lastRoleIsTool(request.getMessages()) && needsCreateAfterMissingFile(request.getMessages())) {
            return firstByNames(request.getTools(), "create_file", "createFile", "write_file", "writeFile")
                    .orElse(request.getTools().getFirst());
        }
        if (lastRoleIsTool(request.getMessages()) && needsEditAfterCreateFailure(request.getMessages())) {
            return firstByNames(
                            request.getTools(),
                            "replace_string_in_file",
                            "insert_edit_into_file",
                            "apply_patch",
                            "edit_file",
                            "write_file")
                    .orElse(request.getTools().getFirst());
        }

        JsonNode toolChoice = request.getToolChoice();
        if (toolChoice != null && toolChoice.isObject()) {
            JsonNode nameNode = toolChoice.path("function").path("name");
            if (!nameNode.isMissingNode() && !nameNode.asText().isBlank()) {
                String forced = nameNode.asText();
                return request.getTools().stream()
                        .filter(t -> t.getFunction() != null && forced.equals(t.getFunction().getName()))
                        .findFirst()
                        .orElseGet(() -> namedStub(forced));
            }
        }

        String intent = lastUserContent(request.getMessages());
        String intentLower = intent == null ? "" : intent.toLowerCase(Locale.ROOT);
        ToolDefinition byIntent = matchIntent(request.getTools(), intentLower);
        if (byIntent != null) {
            return byIntent;
        }

        return firstByNames(
                        request.getTools(),
                        "create_file",
                        "createFile",
                        "write_file",
                        "writeFile",
                        "replace_string_in_file",
                        "insert_edit_into_file",
                        "apply_patch",
                        "run_in_terminal",
                        "runInTerminal",
                        "read_file",
                        "readFile",
                        "grep_search",
                        "file_search")
                .orElse(request.getTools().getFirst());
    }

    private ToolDefinition matchIntent(List<ToolDefinition> tools, String intent) {
        if (intent.contains("create") || intent.contains("new file") || intent.contains("write file")) {
            return firstByNames(tools, "create_file", "createFile", "write_file", "writeFile").orElse(null);
        }
        if (intent.contains("edit")
                || intent.contains("replace")
                || intent.contains("update")
                || intent.contains("modify")
                || intent.contains("patch")
                || intent.contains("insert")) {
            return firstByNames(
                            tools,
                            "replace_string_in_file",
                            "insert_edit_into_file",
                            "apply_patch",
                            "edit_file",
                            "write_file")
                    .orElse(null);
        }
        if (intent.contains("terminal")
                || intent.contains("run ")
                || intent.contains("command")
                || intent.contains("shell")
                || intent.contains("npm ")
                || intent.contains("mvn ")) {
            return firstByNames(tools, "run_in_terminal", "runInTerminal", "execute_command", "bash")
                    .orElse(null);
        }
        if (intent.contains("search") || intent.contains("grep") || intent.contains("find ")) {
            return firstByNames(tools, "grep_search", "file_search", "semantic_search").orElse(null);
        }
        if (intent.contains("read") || intent.contains("open ") || intent.contains("show ")) {
            return firstByNames(tools, "read_file", "readFile").orElse(null);
        }
        return null;
    }

    private Optional<ToolDefinition> firstByNames(List<ToolDefinition> tools, String... names) {
        for (String name : names) {
            for (ToolDefinition tool : tools) {
                if (tool.getFunction() != null && name.equals(tool.getFunction().getName())) {
                    return Optional.of(tool);
                }
            }
        }
        return Optional.empty();
    }

    private ToolDefinition namedStub(String name) {
        ToolDefinition.FunctionDefinition fn = new ToolDefinition.FunctionDefinition();
        fn.setName(name);
        ToolDefinition tool = new ToolDefinition();
        tool.setFunction(fn);
        return tool;
    }

    private ToolCall buildToolCall(ToolDefinition tool, ChatCompletionRequest request) {
        String name = tool.getFunction() != null ? tool.getFunction().getName() : "unknown";
        JsonNode parameters = tool.getFunction() != null ? tool.getFunction().getParameters() : null;
        ToolCall.FunctionCall fn = new ToolCall.FunctionCall();
        fn.setName(name);
        fn.setArguments(argumentsFor(name, parameters, request));

        ToolCall call = new ToolCall();
        call.setId("call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        call.setType("function");
        call.setFunction(fn);
        return call;
    }

    private String argumentsFor(String name, JsonNode parameters, ChatCompletionRequest request) {
        PathContext ctx = resolvePathContext(request);
        log.info("Remote VS Code workspace={} create={} edit={}", ctx.workspace(), ctx.createTarget(), ctx.editTarget());
        ObjectNode args = objectMapper.createObjectNode();
        String lower = name.toLowerCase(Locale.ROOT);
        String user = lastUserContent(request.getMessages());

        if (parameters != null && parameters.isObject()) {
            JsonNode properties = parameters.path("properties");
            JsonNode required = parameters.path("required");
            if (required.isArray() && !required.isEmpty()) {
                for (JsonNode req : required) {
                    putDefault(args, req.asText(), properties.path(req.asText()), name, user, ctx);
                }
            } else if (properties.isObject() && properties.size() > 0) {
                Iterator<String> names = properties.fieldNames();
                while (names.hasNext()) {
                    String prop = names.next();
                    putDefault(args, prop, properties.path(prop), name, user, ctx);
                }
            }
        }

        if (args.isEmpty()) {
            if (lower.contains("create") || lower.contains("write")) {
                args.put("filePath", ctx.createTarget());
                args.put("content", contentForCreate(user));
            } else if (lower.contains("replace") || lower.contains("edit") || lower.contains("patch")) {
                args.put("filePath", ctx.editTarget());
                args.put("oldString", "placeholder-old");
                args.put("newString", "placeholder-new");
            } else if (lower.contains("terminal")
                    || lower.contains("bash")
                    || lower.contains("command")
                    || lower.contains("shell")) {
                args.put("command", "echo dummy-gateway");
            } else if (lower.contains("read") || lower.contains("file")) {
                args.put("filePath", ctx.editTarget());
            }
        }
        return args.toString();
    }

    private void putDefault(
            ObjectNode args, String prop, JsonNode schema, String toolName, String user, PathContext ctx) {
        if (prop == null || prop.isBlank() || args.has(prop)) {
            return;
        }
        String type = schema != null ? schema.path("type").asText("string") : "string";
        String key = prop.toLowerCase(Locale.ROOT);
        String value = valueForProperty(key, type, toolName, user, ctx);
        if ("boolean".equals(type)) {
            args.put(prop, Boolean.parseBoolean(value));
        } else if ("integer".equals(type) || "number".equals(type)) {
            args.put(prop, 1);
        } else if ("array".equals(type)) {
            args.putArray(prop);
        } else if ("object".equals(type)) {
            args.putObject(prop);
        } else {
            args.put(prop, value);
        }
    }

    private String valueForProperty(String key, String type, String toolName, String user, PathContext ctx) {
        if ("boolean".equals(type)) {
            return "false";
        }
        String lowerTool = toolName.toLowerCase(Locale.ROOT);
        if (key.contains("command") || key.contains("cmd") || key.contains("script")) {
            return "echo dummy-gateway";
        }
        if (key.contains("workdir") || key.equals("cwd") || key.contains("directory") || key.equals("dir")) {
            return ctx.workspace() == null ? "." : ctx.workspace();
        }
        if (key.contains("old") || key.equals("oldstring") || key.equals("old_string")) {
            return "placeholder-old";
        }
        if (key.contains("new") || key.equals("newstring") || key.equals("new_string") || key.equals("replacement")) {
            return contentForCreate(user);
        }
        if (key.contains("content") || key.equals("text") || key.equals("contents")) {
            return contentForCreate(user);
        }
        if (key.contains("path")
                || key.contains("file")
                || key.contains("uri")
                || key.contains("glob")
                || "filename".equals(key)) {
            if (lowerTool.contains("create") || lowerTool.contains("write")) {
                return ctx.createTarget();
            }
            return ctx.editTarget();
        }
        if (key.contains("pattern") || key.contains("query")) {
            return user == null || user.isBlank() ? "*" : truncate(user, 40);
        }
        if (lowerTool.contains("read") || lowerTool.contains("file")) {
            return ctx.editTarget();
        }
        if (lowerTool.contains("terminal") || lowerTool.contains("command") || lowerTool.contains("shell")) {
            return "echo dummy-gateway";
        }
        return "dummy";
    }

    private String contentForCreate(String user) {
        if (user == null || user.isBlank()) {
            return "created-by-gateway\n";
        }
        return truncate(user, 120) + "\n";
    }

    private PathContext resolvePathContext(ChatCompletionRequest request) {
        String corpus = allText(request.getMessages());
        String user = lastUserContent(request.getMessages());
        String workspace = resolveRemoteWorkspace(corpus);
        if (workspace != null && !isInvalidWorkspace(workspace)) {
            lastRemoteWorkspace = workspace;
        }
        String relative = extractRequestedRelative(user).orElse(null);
        String failedPath = extractLastToolFilePath(request.getMessages())
                .or(() -> extractMissingPathFromToolError(request.getMessages()))
                .orElse(null);
        failedPath = sanitizeBadFilePath(failedPath, workspace, relative);
        String absoluteFromUser = extractAbsoluteFromUser(user).orElse(null);
        absoluteFromUser = sanitizeBadFilePath(absoluteFromUser, workspace, relative);
        return new PathContext(workspace, relative, failedPath, absoluteFromUser, isUnixWorkspace(workspace));
    }

    private String resolveRemoteWorkspace(String corpus) {
        if (configuredWorkspace != null && !isInvalidWorkspace(configuredWorkspace)) {
            return configuredWorkspace;
        }
        Optional<String> extracted = extractWorkspace(corpus).filter(ws -> !isInvalidWorkspace(ws));
        if (extracted.isPresent()) {
            return extracted.get();
        }
        if (lastRemoteWorkspace != null && !isInvalidWorkspace(lastRemoteWorkspace)) {
            return lastRemoteWorkspace;
        }
        return null;
    }

    private String sanitizeBadFilePath(String path, String workspace, String relative) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (!isDriveRootFile(path) && !isInvalidWorkspace(parentOf(path))) {
            return path;
        }
        String name = relative != null && !relative.isBlank() ? relative : baseName(path);
        if (name == null || name.isBlank()) {
            name = "README.md";
        }
        if (workspace != null && !isInvalidWorkspace(workspace)) {
            return joinRemote(workspace, name, isUnixWorkspace(workspace));
        }
        return name;
    }

    private boolean isDriveRootFile(String path) {
        if (path == null) {
            return false;
        }
        String n = path.replace('/', '\\');
        return n.matches("(?i)^[A-Za-z]:\\\\[^\\\\]+$");
    }

    private boolean isInvalidWorkspace(String workspace) {
        if (workspace == null || workspace.isBlank()) {
            return true;
        }
        String n = workspace.replace('/', '\\').replaceAll("\\\\+$", "");
        if (n.matches("(?i)^[A-Za-z]:$")) {
            return true;
        }
        return n.equals("/") || n.equals("\\");
    }

    private Optional<String> extractMissingPathFromToolError(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Optional.empty();
        }
        String content = extractContent(messages.getLast());
        Matcher m = Pattern.compile("(?i)(?:file does not exist|does not exist|file not found)\\s*:\\s*(.+)").matcher(content);
        if (m.find()) {
            String path = m.group(1).trim();
            int cut = path.indexOf(". Use ");
            if (cut > 0) {
                path = path.substring(0, cut).trim();
            }
            return Optional.of(normalizeRemote(path));
        }
        return Optional.empty();
    }

    private Optional<String> extractLastToolFilePath(List<ChatMessage> messages) {
        if (messages == null) {
            return Optional.empty();
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.getRole() != null && "assistant".equalsIgnoreCase(m.getRole()) && m.getToolCalls() != null) {
                for (ToolCall call : m.getToolCalls()) {
                    if (call.getFunction() == null || call.getFunction().getArguments() == null) {
                        continue;
                    }
                    try {
                        JsonNode args = objectMapper.readTree(call.getFunction().getArguments());
                        JsonNode path = args.get("filePath");
                        if (path == null) {
                            path = args.get("path");
                        }
                        if (path != null && path.isTextual() && !path.asText().isBlank()) {
                            return Optional.of(normalizeRemote(path.asText()));
                        }
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractWorkspace(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher hint = WORKSPACE_HINT.matcher(text);
        if (hint.find()) {
            String raw = normalizeRemote(hint.group(1).trim());
            return Optional.of(looksLikeFile(raw) ? parentOf(raw) : raw);
        }

        List<String> abs = new ArrayList<>();
        collectPaths(WIN_PATH.matcher(text), abs);
        collectPaths(UNIX_PATH.matcher(text), abs);
        Matcher uri = FILE_URI.matcher(text);
        while (uri.find()) {
            String raw = uri.group(1).replace("%3A", ":").replace("%3a", ":").replace("%2F", "/").replace("%2f", "/");
            if (raw.length() >= 2 && Character.isLetter(raw.charAt(0)) && raw.charAt(1) == ':') {
                raw = raw.charAt(0) + ":" + raw.substring(2).replace('/', '\\');
            }
            abs.add(normalizeRemote(raw));
        }
        abs.removeIf(p -> isGatewayLocalPath(p) || isDriveRootFile(p));
        if (abs.isEmpty()) {
            return Optional.empty();
        }

        List<String> roots = new ArrayList<>();
        for (String file : abs) {
            projectRootFrom(file).filter(ws -> !isInvalidWorkspace(ws)).ifPresent(roots::add);
        }
        if (roots.isEmpty()) {
            return Optional.empty();
        }
        String common = roots.getFirst();
        for (int i = 1; i < roots.size(); i++) {
            String next = longestCommonPath(common, roots.get(i));
            if (next == null || next.length() < 2) {
                return Optional.of(roots.getFirst());
            }
            common = next;
        }
        return Optional.of(common);
    }

    private boolean isGatewayLocalPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        return lower.contains("/open_ai_api") || lower.contains("/open_ai_cursor_api");
    }

    private Optional<String> projectRootFrom(String file) {
        String dir = looksLikeFile(file) ? parentOf(file) : file;
        if (dir == null || dir.isBlank()) {
            return Optional.empty();
        }
        String cursor = dir;
        while (true) {
            String parent = parentOf(cursor);
            if (parent == null) {
                return Optional.of(dir);
            }
            String parentName = baseName(parent);
            String name = baseName(cursor);
            if (PROJECT_PARENTS.contains(parentName) && !SOURCEY.contains(name.toLowerCase(Locale.ROOT))) {
                return Optional.of(cursor);
            }
            if (parentName.equalsIgnoreCase("home") || parentName.equals("Users") || parentName.equals("users")) {
                return Optional.of(cursor);
            }
            if (parent.equals(cursor)) {
                return Optional.of(cursor);
            }
            cursor = parent;
            if (segments(cursor).size() <= 1) {
                return Optional.of(dir);
            }
        }
    }

    private String longestCommonPath(String a, String b) {
        boolean unix = a.startsWith("/");
        String sep = unix ? "/" : "\\";
        String[] as = segments(a).toArray(String[]::new);
        String[] bs = segments(b).toArray(String[]::new);
        int n = Math.min(as.length, bs.length);
        int i = 0;
        while (i < n && as[i].equalsIgnoreCase(bs[i])) {
            i++;
        }
        if (i == 0) {
            return unix ? "/" : (as.length > 0 ? as[0] + "\\" : null);
        }
        StringBuilder sb = new StringBuilder();
        if (unix) {
            sb.append('/');
        }
        for (int j = 0; j < i; j++) {
            if (j > 0) {
                sb.append(sep);
            }
            sb.append(as[j]);
        }
        if (!unix && i == 1 && as[0].endsWith(":")) {
            sb.append('\\');
        }
        return sb.toString();
    }

    private void collectPaths(Matcher matcher, List<String> abs) {
        while (matcher.find()) {
            abs.add(normalizeRemote(matcher.group(1)));
        }
    }

    private Optional<String> extractRequestedRelative(String user) {
        if (user == null || user.isBlank()) {
            return Optional.empty();
        }
        Matcher m = REQUESTED_FILE.matcher(user);
        if (m.find()) {
            String name = m.group(1);
            if (isAbsoluteRemote(name)) {
                return Optional.empty();
            }
            return Optional.of(name.replace('\\', '/'));
        }
        Matcher bare = BARE_FILE.matcher(user);
        if (bare.find()) {
            String name = bare.group(1);
            if (!name.equalsIgnoreCase("readme.md")) {
                return Optional.of(name.replace('\\', '/'));
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractAbsoluteFromUser(String user) {
        if (user == null || user.isBlank()) {
            return Optional.empty();
        }
        Matcher m = REQUESTED_FILE.matcher(user);
        if (m.find() && isAbsoluteRemote(m.group(1))) {
            return Optional.of(normalizeRemote(m.group(1)));
        }
        Matcher win = WIN_PATH.matcher(user);
        if (win.find()) {
            return Optional.of(normalizeRemote(win.group(1)));
        }
        Matcher unix = UNIX_PATH.matcher(user);
        if (unix.find()) {
            return Optional.of(normalizeRemote(unix.group(1)));
        }
        return Optional.empty();
    }

    private String allText(List<ChatMessage> messages) {
        if (messages == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            sb.append(extractContent(m)).append('\n');
            if (m.getToolCalls() != null) {
                for (ToolCall call : m.getToolCalls()) {
                    if (call.getFunction() != null && call.getFunction().getArguments() != null) {
                        sb.append(call.getFunction().getArguments()).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    private String buildTextReply(ChatCompletionRequest request) {
        if (lastRoleIsTool(request.getMessages())) {
            String toolContent = extractContent(request.getMessages().getLast());
            String lower = toolContent.toLowerCase(Locale.ROOT);
            if (lower.contains("error")
                    || lower.contains("does not exist")
                    || lower.contains("not found")
                    || lower.contains("failed")) {
                return "Previous tool failed: " + truncate(toolContent, 500);
            }
            return "Tool completed successfully. Result: " + truncate(toolContent, 500);
        }
        String user = lastUserContent(request.getMessages());
        if (user == null || user.isBlank()) {
            return "Hello from dummy-gateway.";
        }
        return "Dummy reply to: " + truncate(user, 200);
    }

    private String lastUserContent(List<ChatMessage> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.getRole() != null && "user".equalsIgnoreCase(m.getRole())) {
                return extractContent(m);
            }
        }
        return null;
    }

    private String extractContent(ChatMessage message) {
        JsonNode content = message.getContent();
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.has("text")) {
                    if (!sb.isEmpty()) {
                        sb.append(' ');
                    }
                    sb.append(part.get("text").asText(""));
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static String normalizeRemote(String path) {
        if (path == null) {
            return null;
        }
        String p = path.trim().replace("\"", "").replace("'", "");
        if (p.startsWith("vscode-remote://")) {
            int idx = p.indexOf('/');
            // vscode-remote://ssh-remote+host/home/user/proj → /home/user/proj
            int slash = p.indexOf('/', "vscode-remote://".length());
            if (slash > 0) {
                p = p.substring(slash);
            }
        }
        if (p.startsWith("/") || p.startsWith("\\")) {
            return p.replace('\\', '/').replaceAll("/+", "/");
        }
        if (p.length() >= 2 && Character.isLetter(p.charAt(0)) && p.charAt(1) == ':') {
            return p.replace('/', '\\');
        }
        return p;
    }

    private static boolean isAbsoluteRemote(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return path.startsWith("/")
                || path.startsWith("\\")
                || (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':')
                || path.startsWith("vscode-remote://");
    }

    private static boolean isUnixWorkspace(String workspace) {
        return workspace != null && workspace.startsWith("/");
    }

    private static boolean looksLikeFile(String path) {
        String base = baseName(path);
        return base.contains(".") && !base.startsWith(".");
    }

    private static String baseName(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace('\\', '/');
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private static String parentOf(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        boolean win = path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
        String sep = win ? "\\" : "/";
        String normalized = win ? path.replace('/', '\\') : path.replace('\\', '/');
        while (normalized.endsWith(sep) && normalized.length() > sep.length()) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int i = normalized.lastIndexOf(sep);
        if (i <= 0) {
            return win ? normalized.substring(0, 2) + "\\" : "/";
        }
        if (win && i == 2) {
            return normalized.substring(0, 3);
        }
        return normalized.substring(0, i);
    }

    private static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        boolean win = path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
        if (win) {
            out.add(path.substring(0, 2));
            String rest = path.length() > 3 ? path.substring(3) : "";
            for (String s : rest.split("\\\\|/")) {
                if (!s.isBlank()) {
                    out.add(s);
                }
            }
        } else {
            for (String s : path.split("/")) {
                if (!s.isBlank()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static String joinRemote(String workspace, String relative, boolean unix) {
        if (workspace == null || workspace.isBlank()) {
            return relative;
        }
        String rel = relative.replace('\\', '/');
        while (rel.startsWith("./")) {
            rel = rel.substring(2);
        }
        if (unix || workspace.startsWith("/")) {
            String root = workspace.endsWith("/") ? workspace.substring(0, workspace.length() - 1) : workspace;
            return root + "/" + rel;
        }
        String root = workspace.endsWith("\\") ? workspace.substring(0, workspace.length() - 1) : workspace;
        return root + "\\" + rel.replace('/', '\\');
    }

    private record PathContext(
            String workspace,
            String requestedRelative,
            String failedPath,
            String absoluteFromUser,
            boolean unix) {
        String createTarget() {
            if (failedPath != null && !failedPath.isBlank()) {
                return failedPath;
            }
            if (absoluteFromUser != null) {
                return absoluteFromUser;
            }
            if (requestedRelative != null && !requestedRelative.isBlank() && workspace != null) {
                return joinRemote(workspace, requestedRelative, unix);
            }
            if (requestedRelative != null && !requestedRelative.isBlank()) {
                return requestedRelative;
            }
            String unique = "copilot-create-" + UUID.randomUUID().toString().substring(0, 8) + ".txt";
            if (workspace != null) {
                return joinRemote(workspace, unique, unix);
            }
            return unique;
        }

        String editTarget() {
            if (failedPath != null && !failedPath.isBlank()) {
                return failedPath;
            }
            if (absoluteFromUser != null) {
                return absoluteFromUser;
            }
            if (requestedRelative != null && !requestedRelative.isBlank() && workspace != null) {
                return joinRemote(workspace, requestedRelative, unix);
            }
            if (workspace != null) {
                return joinRemote(workspace, "README.md", unix);
            }
            return "README.md";
        }
    }
}

