package com.example.aijobs.assistant;

import com.example.aijobs.AiJobPlatformApplication;
import com.example.aijobs.deepseek.DeepSeekChatClient;
import com.example.aijobs.deepseek.DeepSeekChatResult;
import com.example.aijobs.deepseek.DeepSeekMessage;
import com.example.aijobs.deepseek.DeepSeekProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/** Explicit opt-in only: real browser, HTTP/auth, MySQL and at most three official model calls. */
@EnabledIfEnvironmentVariable(named = "ASSISTANT_BROWSER_MYSQL_LIVE_TEST", matches = "true")
@SpringBootTest(classes = AiJobPlatformApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "app.jwt.secret=day38-isolated-browser-test-secret-at-least-32-bytes",
                "app.jwt.expiration=PT2H", "app.deepseek.enabled=true", "app.deepseek.api-key=${DEEPSEEK_API_KEY:}",
                "app.deepseek.max-tokens=2048", "app.deepseek.request-timeout=PT30S", "app.assistant.max-history-messages=8",
                "app.external-jobs.enabled=false", "spring.sql.init.mode=never", "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.timeout=200ms", "spring.data.redis.connect-timeout=200ms",
                "spring.data.redis.password=", "spring.data.redis.username=", "spring.data.redis.database=0"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecruitmentBrowserMysqlLiveIT {
    private static final Pattern SAFE_DATABASE_URL = Pattern.compile(
            "jdbc:mysql://127\\.0\\.0\\.1:3306/(ai_job_platform_day38_[0-9]+)(?:\\?[^#]*)?");
    private static final Set<String> SAFE_CONNECTION_OPTIONS = Set.of("useUnicode=true", "characterEncoding=utf8",
            "serverTimezone=Asia/Shanghai", "allowPublicKeyRetrieval=true", "useSSL=false");
    private static final List<String> TABLES = List.of("ai_match_result", "job_application", "job_posting",
            "platform_user", "resume", "role", "user_role");
    private static final String FIRST = "SYNTHETIC_DAY38 student: Give one short Redis evidence-check question. Do not score or decide hiring.";
    private static final String FOLLOWUP = "SYNTHETIC_DAY38 follow-up: Use the previous question and give one short next step for human review.";
    private static final String HR = "SYNTHETIC_DAY38 HR: Give one short verification question about the Redis gap. A human decides hiring.";
    private static final List<String> QUESTIONS = List.of(FIRST, FOLLOWUP, HR);
    private static final String SOURCE_TEXT = "SYNTHETIC_DAY38 fictional exercise only: Java, Spring Boot and MySQL practice.";
    private static final String STUDENT_ACCOUNT = "synthetic_day38_student";
    private static final String HR_ACCOUNT = "synthetic_day38_hr";
    private static ServerSocket unavailableRedisPort;
    private static String databaseName;

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RedisConnectionFactory redisConnectionFactory;
    @Autowired DeepSeekProperties properties;
    @SpyBean DeepSeekChatClient adapter;
    private final Object callLock = new Object();
    private final List<HttpObservation> fixtureHttp = new ArrayList<>();
    private final List<RecordedCall> calls = new ArrayList<>();
    private final List<String> credentials = new ArrayList<>();
    private final Instant startedAt = Instant.now();
    private final String accountPassword = "Synthetic38-" + UUID.randomUUID();
    private final Path outputDirectory = Path.of(System.getProperty("basedir", "."), "target", "live-browser-mysql",
            UUID.randomUUID().toString()).toAbsolutePath().normalize();
    private Map<String, JsonNode> before;
    private Map<String, JsonNode> after;
    private JsonNode browserResults;
    private String mysqlVersion;
    private int attempts;
    private boolean incompleteResult;
    private boolean verified;
    private long jobId;
    private long resumeId;
    private long applicationId;
    private long matchId;

    @DynamicPropertySource
    static void isolatedResources(DynamicPropertyRegistry registry) throws Exception {
        String url = System.getenv("DB_URL");
        var matcher = SAFE_DATABASE_URL.matcher(url == null ? "" : url);
        if (!matcher.matches()) throw new IllegalStateException("DB_URL must identify a loopback ai_job_platform_day38_<digits> schema");
        int query = url.indexOf('?');
        if (query >= 0) {
            List<String> options = List.of(url.substring(query + 1).split("&", -1));
            if (!SAFE_CONNECTION_OPTIONS.containsAll(options) || Set.copyOf(options).size() != options.size()) {
                throw new IllegalStateException("DB_URL contains unsupported or repeated connection options");
            }
        }
        for (String required : List.of("DB_PASSWORD", "DEEPSEEK_API_KEY")) {
            if (System.getenv(required) == null || System.getenv(required).isBlank()) {
                throw new IllegalStateException(required + " is required for the explicit isolated live check");
            }
        }
        databaseName = matcher.group(1);
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("DB_USERNAME", "root"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        // The reserved non-Redis endpoint exercises production timeout/fallback without touching shared keys.
        unavailableRedisPort = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        registry.add("spring.data.redis.port", unavailableRedisPort::getLocalPort);
        registry.add("spring.data.redis.url", () -> "redis://127.0.0.1:" + unavailableRedisPort.getLocalPort());
    }

    @AfterAll
    static void releaseReservedPort() throws Exception {
        if (unavailableRedisPort != null) unavailableRedisPort.close();
    }

    @Test
    void browserStudentHistoryAndHrHumanReviewPreserveRealMysqlData() throws Exception {
        verifyIsolatedResources();
        credentials.add(properties.apiKey());
        credentials.add(System.getenv("DB_PASSWORD"));
        credentials.add(accountPassword);
        Files.createDirectories(outputDirectory.getParent());
        Files.createDirectory(outputDirectory);
        observeRealAdapter();
        try {
            createSyntheticFixturesThroughHttp();
            assertEquals(0, attempts, "Fixture creation and rule scoring must never call the model");
            before = snapshot();
            try {
                runBrowser();
            } finally {
                // Capture all seven tables even when browser assertions or official calls fail.
                after = snapshot();
            }
            assertTrue(before.equals(after), "Every row and column must remain unchanged during browser assistant operations");
            assertPersistedRuleAndApplication();
            synchronized (callLock) {
                assertEquals(3, attempts);
                assertEquals(3, calls.size());
                assertFalse(incompleteResult);
                for (RecordedCall call : calls) assertTrue(isCompleteResponse(call.result()), "Every official reply must be complete with token evidence");
            }
            verified = true;
        } finally {
            saveEvidence();
        }
        System.out.println("LIVE_BROWSER_MYSQL_STATUS generated=3/3 browser=REAL persistence=REAL_MYSQL humanQuality=UNVERIFIED redis=PRODUCTION_FALLBACK");
    }

    private void verifyIsolatedResources() {
        LettuceConnectionFactory redis = assertInstanceOf(LettuceConnectionFactory.class, redisConnectionFactory);
        assertNull(redis.getClusterConfiguration(), "The isolated check must not use a Redis cluster");
        assertNull(redis.getSentinelConfiguration(), "The isolated check must not use Redis Sentinel");
        assertEquals("127.0.0.1", redis.getHostName());
        assertEquals(unavailableRedisPort.getLocalPort(), redis.getPort());
        assertEquals(databaseName, jdbc.queryForObject("SELECT DATABASE()", String.class));
        mysqlVersion = jdbc.queryForObject("SELECT VERSION()", String.class);
        assertTrue(mysqlVersion != null && mysqlVersion.startsWith("8."), "This acceptance targets MySQL 8");
        assertEquals(TABLES, jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' ORDER BY table_name", String.class));
        for (String table : TABLES) {
            if (!table.equals("role")) assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class),
                    "Refusing a nonempty isolated business table: " + table);
        }
        assertEquals(List.of("ADMIN", "HR", "STUDENT"), jdbc.queryForList("SELECT code FROM role ORDER BY code", String.class));
    }

    private void observeRealAdapter() {
        doAnswer(invocation -> {
            synchronized (callLock) {
                assertFalse(incompleteResult, "An incomplete result blocks every subsequent official call");
                assertTrue(attempts < QUESTIONS.size(), "Hard limit: at most three official attempts, no retries");
                List<DeepSeekMessage> messages = invocation.getArgument(0);
                int index = attempts;
                assertEquals(index == 1 ? 5 : 3, messages.size());
                assertEquals("system", messages.getFirst().role());
                assertTrue(messages.getFirst().content().contains(index == 2 ? "HR 招聘复核" : "学生求职准备"));
                assertEquals("user", messages.get(1).role());
                assertTrue(messages.get(1).content().contains("SYNTHETIC_DAY38 fictional CV"));
                assertTrue(messages.get(1).content().contains("SYNTHETIC_DAY38 teaching assistant"));
                assertTrue(messages.get(1).content().contains(SOURCE_TEXT));
                assertTrue(messages.get(1).content().contains("只读匹配分：75.00"));
                assertTrue(messages.get(1).content().contains("投递状态：SUBMITTED"));
                assertEquals("user", messages.getLast().role());
                assertTrue(QUESTIONS.get(index).equals(messages.getLast().content()), "Only the next fixed synthetic question may leave the process");
                if (index == 1) {
                    assertTrue(FIRST.equals(messages.get(2).content()));
                    assertEquals("user", messages.get(2).role());
                    assertEquals("assistant", messages.get(3).role());
                    assertTrue(calls.getFirst().result().content().equals(messages.get(3).content()), "History must contain the actual previous official reply");
                }
                attempts++;
                String start = Instant.now().toString();
                DeepSeekChatResult result;
                try {
                    result = (DeepSeekChatResult) invocation.callRealMethod();
                } catch (Throwable failure) {
                    incompleteResult = true;
                    throw failure;
                }
                calls.add(new RecordedCall(start, Instant.now().toString(), List.copyOf(messages), result));
                incompleteResult = !isCompleteResponse(result);
                return result;
            }
        }).when(adapter).generate(anyList());
    }

    private void createSyntheticFixturesThroughHttp() throws Exception {
        String studentToken = registerAndLogin(STUDENT_ACCOUNT, "STUDENT");
        String hrToken = registerAndLogin(HR_ACCOUNT, "HR");
        jobId = request("create-job", "POST", "/api/hr/jobs", hrToken, Map.of(
                "title", "SYNTHETIC_DAY38 teaching assistant", "companyName", "SYNTHETIC_DAY38 fictional school",
                "city", "Synthetic city", "employmentType", "PART_TIME", "description", "SYNTHETIC_DAY38 teaching support exercise",
                "requirements", "Java, Spring Boot, MySQL, Redis"), 201).path("data").path("id").asLong();
        assertTrue(jobId > 0);
        request("publish-job", "PATCH", "/api/hr/jobs/" + jobId + "/status", hrToken, Map.of("status", "PUBLISHED"), 200);
        resumeId = request("create-resume", "POST", "/api/student/resumes", studentToken, Map.of(
                "title", "SYNTHETIC_DAY38 fictional CV", "skills", "Java, Spring Boot", "sourceText", SOURCE_TEXT),
                201).path("data").path("id").asLong();
        assertTrue(resumeId > 0);
        request("publish-resume", "PATCH", "/api/student/resumes/" + resumeId + "/status", studentToken, Map.of("status", "PUBLISHED"), 200);
        applicationId = request("submit-application", "POST", "/api/student/applications", studentToken,
                Map.of("jobId", jobId, "resumeId", resumeId), 201).path("data").path("id").asLong();
        assertTrue(applicationId > 0);
        JsonNode match = request("compute-rule-match", "POST", "/api/student/matches", studentToken,
                Map.of("jobId", jobId, "resumeId", resumeId), 200).path("data");
        matchId = match.path("id").asLong();
        assertTrue(matchId > 0);
        assertEquals(0, new BigDecimal("75.00").compareTo(match.path("score").decimalValue()));
        assertEquals("简历文本尚未识别的岗位技能：Redis；未提及不等于不具备。", match.path("gapSummary").asText());
        assertEquals(SOURCE_TEXT, jdbc.queryForObject("SELECT source_text FROM resume WHERE id = ?", String.class, resumeId));
        assertPersistedRuleAndApplication();
    }

    private String registerAndLogin(String username, String role) throws Exception {
        request("register-" + role.toLowerCase(), "POST", "/api/auth/register", null,
                Map.of("username", username, "password", accountPassword, "email", username + "@example.invalid", "role", role), 201);
        JsonNode login = request("login-" + role.toLowerCase(), "POST", "/api/auth/login", null,
                Map.of("account", username, "password", accountPassword), 200).path("data");
        String token = login.path("accessToken").asText();
        assertFalse(token.isBlank(), "Login must return an accessToken");
        credentials.add(token);
        assertTrue(login.path("roles").toString().contains(role));
        return token;
    }

    private JsonNode request(String label, String method, String path, String token, Map<String, ?> body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode parsed = json.readTree(response.body());
            fixtureHttp.add(new HttpObservation(label, method, path, response.statusCode(), parsed.path("success").asBoolean()));
            assertEquals(expected, response.statusCode(), label);
            assertTrue(parsed.path("success").asBoolean(), label);
            return parsed;
        }
    }

    private void runBrowser() throws Exception {
        Path backend = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        Path script = backend.resolve("../frontend/tests/assistant-mysql-browser.cjs").normalize();
        assertTrue(Files.isRegularFile(script), "The real browser script must exist");
        ProcessBuilder builder = new ProcessBuilder(System.getenv().getOrDefault("DAY38_NODE", "node"), script.toString())
                .directory(backend.getParent().toFile()).redirectErrorStream(true)
                .redirectOutput(outputDirectory.resolve("browser-process.log").toFile());
        Map<String, String> env = builder.environment();
        for (String secret : List.of("DB_PASSWORD", "MYSQL_PWD", "DEEPSEEK_API_KEY", "JWT_SECRET", "DB_URL", "DB_USERNAME")) env.remove(secret);
        env.put("DAY38_BACKEND_URL", "http://127.0.0.1:" + port);
        env.put("DAY38_STUDENT_ACCOUNT", STUDENT_ACCOUNT);
        env.put("DAY38_HR_ACCOUNT", HR_ACCOUNT);
        env.put("DAY38_PASSWORD", accountPassword);
        env.put("DAY38_APPLICATION_ID", Long.toString(applicationId));
        env.put("DAY38_OUTPUT_DIR", outputDirectory.toString());
        Process browser = builder.start();
        try {
            assertTrue(browser.waitFor(240, TimeUnit.SECONDS), "Browser timed out; inspect the private process log");
            Path result = outputDirectory.resolve("browser-results.json");
            assertTrue(Files.isRegularFile(result), "Browser result is missing; inspect the private process log");
            String serialized = Files.readString(result, StandardCharsets.UTF_8);
            assertCredentialFree(serialized);
            browserResults = json.readTree(serialized);
            assertEquals(0, browser.exitValue(), "Browser checks failed; inspect the private result and log");
            assertTrue(browserResults.path("passed").asBoolean(), "Browser checks must all pass");
        } finally {
            if (browser.isAlive()) terminateProcessTree(browser);
        }
    }

    private void terminateProcessTree(Process process) throws InterruptedException {
        // Only handles rooted at the exact subprocess started above are eligible; never kill by process name.
        List<ProcessHandle> descendants = process.descendants().toList();
        for (ProcessHandle child : descendants.reversed()) if (child.isAlive()) child.destroy();
        process.destroy();
        boolean interrupted = false;
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            interrupted = true;
        } finally {
            for (ProcessHandle child : descendants.reversed()) if (child.isAlive()) child.destroyForcibly();
            if (process.isAlive()) process.destroyForcibly();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void assertPersistedRuleAndApplication() {
        assertEquals(0, new BigDecimal("75.00").compareTo(jdbc.queryForObject("SELECT score FROM ai_match_result WHERE id = ?", BigDecimal.class, matchId)));
        assertEquals("local-skill-coverage-v1", jdbc.queryForObject("SELECT model_name FROM ai_match_result WHERE id = ?", String.class, matchId));
        assertEquals("简历文本尚未识别的岗位技能：Redis；未提及不等于不具备。", jdbc.queryForObject("SELECT gap_summary FROM ai_match_result WHERE id = ?", String.class, matchId));
        assertEquals("SUBMITTED", jdbc.queryForObject("SELECT status FROM job_application WHERE id = ?", String.class, applicationId));
    }

    private Map<String, JsonNode> snapshot() {
        Map<String, JsonNode> snapshot = new LinkedHashMap<>();
        for (String table : TABLES) snapshot.put(table, json.valueToTree(jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id")));
        return snapshot;
    }

    private Map<String, Object> snapshotEvidence(Map<String, JsonNode> snapshot) throws Exception {
        if (snapshot == null) return Map.of("status", "not-reached");
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (var entry : snapshot.entrySet()) evidence.put(entry.getKey(), Map.of("rowCount", entry.getValue().size(),
                "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(entry.getValue())))));
        return evidence;
    }

    private boolean isCompleteResponse(DeepSeekChatResult result) {
        return result != null && result.status() == DeepSeekChatResult.Status.GENERATED
                && Integer.valueOf(200).equals(result.httpStatus()) && "stop".equals(result.finishReason())
                && !result.truncated() && !result.content().isBlank() && result.usage() != null
                && result.usage().promptTokens() > 0 && result.usage().completionTokens() > 0
                && (long) result.usage().promptTokens() + result.usage().completionTokens() == result.usage().totalTokens();
    }

    private void assertCredentialFree(String serialized) {
        for (String credential : credentials) assertFalse(credential != null && !credential.isBlank() && serialized.contains(credential),
                "Refusing to persist a credential or authentication token");
        assertFalse(Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}").matcher(serialized).find(),
                "Refusing to persist a browser-issued JWT");
    }

    private void saveEvidence() throws Exception {
        String sourceCommit = System.getenv().getOrDefault("EVALUATION_SOURCE_COMMIT", "not-provided");
        assertTrue(sourceCommit.equals("not-provided") || sourceCommit.matches("[0-9a-fA-F]{40,64}"), "Source commit must be a full Git SHA");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("startedAtUtc", startedAt.toString());
        evidence.put("completedAtUtc", Instant.now().toString());
        evidence.put("sourceCommit", sourceCommit);
        evidence.put("passed", verified);
        evidence.put("executionPath", "Real browser/Vue/loopback Spring HTTP/auth/JWT/MyBatis/MySQL/rules/chat/official DeepSeek; no substitutes");
        evidence.put("database", Map.of("schema", databaseName, "version", mysqlVersion));
        evidence.put("configuration", Map.of("model", properties.model(), "maxTokens", properties.maxTokens(),
                "requestTimeout", properties.requestTimeout().toString(), "endpoint", "https://api.deepseek.com/chat/completions"));
        evidence.put("fixtureIds", Map.of("jobId", jobId, "resumeId", resumeId, "applicationId", applicationId, "matchId", matchId));
        evidence.put("ruleExpectation", Map.of("score", "75.00", "missingSkill", "Redis", "applicationStatus", "SUBMITTED", "sourceText", SOURCE_TEXT));
        evidence.put("databaseBeforeBrowser", snapshotEvidence(before));
        evidence.put("databaseAfterBrowser", snapshotEvidence(after));
        evidence.put("databaseUnchanged", before != null && before.equals(after));
        evidence.put("fixtureHttpObservations", fixtureHttp);
        evidence.put("browserResults", browserResults);
        synchronized (callLock) {
            evidence.put("adapterAttemptCount", attempts);
            evidence.put("blockedAfterIncompleteResult", incompleteResult);
            evidence.put("adapterCalls", List.copyOf(calls));
        }
        evidence.put("limitations", List.of("Only fixed synthetic data in a new isolated MySQL database; no production migration or real CV acceptance.",
                "Redis intentionally unavailable on a reserved loopback port; production timeout/fallback exercised, Redis success unverified.",
                "Browser behavior and actual official responses do not establish human-reviewed quality, measurable model benefit or real collaboration history."));
        String serialized = json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence);
        assertCredentialFree(serialized);
        Path output = outputDirectory.resolve("raw-evidence.json");
        Files.writeString(output, serialized, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("LIVE_BROWSER_MYSQL_EVIDENCE=" + output);
    }

    record HttpObservation(String label, String method, String path, int status, boolean success) { }
    record RecordedCall(String startedAtUtc, String completedAtUtc, List<DeepSeekMessage> messages, DeepSeekChatResult result) { }
}
