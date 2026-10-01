package com.opensource.docgrid.domain.dashboard.benchmark;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.auth.websocket.StompSessionRegistry;
import com.opensource.docgrid.domain.dashboard.controller.DashboardWebSocketController;
import com.opensource.docgrid.domain.dashboard.dto.response.DashboardSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.DocumentsSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.JobsSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.SearchSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.WorkersSummaryResponse;

/**
 * GCP의 실제 앱·Redis·OpenProxy·OpenSQL 경로로 합성 대시보드 메시지를 발행하는 시험 전용 fixture.
 *
 * <p>일반 테스트에서 제외된 전용 태그로만 실행한다. 두 비교 버전에 동일한 파일을 적용하며,
 * 시험 전용 계정·JWT는 실행 디렉터리에만 두고 종료 시 계정을 정리한다. 각 JVM이 소유한
 * 인증 완료 STOMP 세션 수만 함께 기록하며, 문서 집계 SQL과 임베딩 부하는 만들지 않는다.
 * 일방향 발행 시험에서는 B의 발행만 끄되 동일한 백엔드와 구독 경로를 유지한다.
 */
@Tag("dashboard-cloud-load")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class DashboardCloudLoadTest {

    private static final Pattern RUN_ID = Pattern.compile("[a-zA-Z0-9_-]{4,64}");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private DashboardWebSocketController dashboardWebSocketController;

    @Autowired
    private StompSessionRegistry stompSessionRegistry;

    @Test
    void publishUntilStopped() throws Exception {
        String runId = System.getProperty("dashboard.load.runId", "");
        if (!RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("dashboard.load.runId는 영숫자·밑줄·하이픈 4~64자여야 합니다.");
        }
        String outputDirectory = System.getProperty("dashboard.load.outputDir", "");
        if (outputDirectory.isBlank()) {
            throw new IllegalArgumentException("dashboard.load.outputDir를 지정해야 합니다.");
        }
        long maxSeconds = Long.parseLong(System.getProperty("dashboard.load.maxSeconds", "900"));
        boolean publish = Boolean.parseBoolean(System.getProperty("dashboard.load.publish", "true"));
        if (maxSeconds < 1 || maxSeconds > 3600) {
            throw new IllegalArgumentException("dashboard.load.maxSeconds는 1~3600초여야 합니다.");
        }

        // 1. 비교 버전마다 새로운 계정을 만들고, 토큰은 처음부터 0600 파일에만 기록한다.
        Path output = Path.of(outputDirectory).toAbsolutePath().normalize();
        Files.createDirectories(output);
        Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwx------"));
        List<Long> testUserIds = new ArrayList<>();
        try {
            Long adminId = createAdmin(runId + "-operator", testUserIds);
            Long subscriberId = createAdmin(runId + "-subscriber", testUserIds);
            writeSecret(output.resolve("operator.jwt"), jwtProvider.generateToken(adminId,
                emailFor(runId + "-operator")));
            writeSecret(output.resolve("subscriber.jwt"), jwtProvider.generateToken(subscriberId,
                emailFor(runId + "-subscriber")));
            Files.writeString(output.resolve("subscriber-id"), subscriberId.toString(), StandardCharsets.US_ASCII);
            Files.createFile(output.resolve("ready"));

            // 2. 이전/신규 코드에 똑같이 300ms 간격으로 실제 Controller 전송 경로를 호출한다.
            long deadline = System.nanoTime() + maxSeconds * 1_000_000_000L;
            long nextTick = System.nanoTime();
            long sequence = 0;
            try (PrintWriter events = new PrintWriter(Files.newBufferedWriter(output.resolve("publisher-events.tsv"),
                StandardCharsets.UTF_8))) {
                events.println("시각(KST)\t이벤트\t순번\t전송시간(ms)\t오류종류\t활성인증세션");
                while (System.nanoTime() < deadline && !Files.exists(output.resolve("stop"))) {
                    long remainingNanos = nextTick - System.nanoTime();
                    if (remainingNanos > 0) {
                        Thread.sleep(remainingNanos / 1_000_000L, (int) (remainingNanos % 1_000_000L));
                    }
                    long sentEpochMillis = System.currentTimeMillis();
                    long start = System.nanoTime();
                    if (!publish) {
                        // 메시지 발생원이 없는 B도 연결을 유지해 브로커 간 전달 여부만 격리한다.
                        events.printf("%s\t미발행\t0\t0.000\t-\t%d\n", OffsetDateTime.now(KST),
                            stompSessionRegistry.authenticatedSessionCount());
                        events.flush();
                        nextTick += 300_000_000L;
                        continue;
                    }
                    try {
                        dashboardWebSocketController.sendDashboardUpdate(summary(++sequence, sentEpochMillis));
                        // 각 백엔드 로컬 registry를 읽어 LB 뒤의 실제 인증 세션 분포를 남긴다.
                        events.printf("%s\t성공\t%d\t%.3f\t-\t%d\n", OffsetDateTime.now(KST), sequence,
                            (System.nanoTime() - start) / 1_000_000.0,
                            stompSessionRegistry.authenticatedSessionCount());
                    } catch (RuntimeException error) {
                        // 예외 메시지에는 내부 주소가 들어갈 수 있으므로 종류만 남긴다.
                        events.printf("%s\t실패\t%d\t%.3f\t%s\t%d\n", OffsetDateTime.now(KST), sequence,
                            (System.nanoTime() - start) / 1_000_000.0, error.getClass().getSimpleName(),
                            stompSessionRegistry.authenticatedSessionCount());
                    }
                    events.flush();
                    nextTick += 300_000_000L;
                }
            }
        } finally {
            // 3. 정상 종료·시험 실패 모두 시험 전용 사용자와 역할 연결만 정리한다.
            RuntimeException cleanupFailure = null;
            for (Long userId : testUserIds) {
                try {
                    jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", userId);
                    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
                } catch (RuntimeException error) {
                    cleanupFailure = error;
                }
            }
            Files.deleteIfExists(output.resolve("operator.jwt"));
            Files.deleteIfExists(output.resolve("subscriber.jwt"));
            Files.deleteIfExists(output.resolve("ready"));
            Files.writeString(output.resolve("finished"), cleanupFailure == null
                ? "시험 계정 2개 정리 완료\n" : "시험 계정 정리 미완료: 수동 확인 필요\n",
                StandardCharsets.UTF_8);
            if (cleanupFailure != null) {
                throw new IllegalStateException("시험 계정 정리 미완료", cleanupFailure);
            }
        }
    }

    private Long createAdmin(String suffix, List<Long> testUserIds) {
        String email = emailFor(suffix);
        Long userId = jdbcTemplate.queryForObject(
            "INSERT INTO users (email, password_hash, name, status) VALUES (?, ?, ?, 'ACTIVE') RETURNING id",
            Long.class, email, "benchmark-only-no-password-login", "Dashboard Load Test");
        testUserIds.add(userId);
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_id, assigned_at) "
                + "VALUES (?, (SELECT id FROM roles WHERE code = 'ADMIN'), CURRENT_TIMESTAMP)", userId);
        return userId;
    }

    private String emailFor(String suffix) {
        return "dashboard-benchmark-" + suffix + "@example.invalid";
    }

    private void writeSecret(Path path, String value) throws IOException {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }

    private DashboardSummaryResponse summary(long sequence, long sentEpochMillis) {
        // test fixture에서만 기존 숫자 필드에 순번과 발행 시각을 넣어 앱 코드 변경 없이 측정한다.
        return new DashboardSummaryResponse(
            new DocumentsSummaryResponse(sequence, sentEpochMillis, 0),
            new JobsSummaryResponse(0, 0, 0, null),
            new WorkersSummaryResponse(0, 0),
            new SearchSummaryResponse(0)
        );
    }
}
