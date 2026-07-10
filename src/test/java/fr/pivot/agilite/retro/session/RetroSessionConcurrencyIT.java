package fr.pivot.agilite.retro.session;

import fr.pivot.agilite.retro.session.dto.CreateRetroSessionRequest;
import fr.pivot.agilite.retro.session.dto.RetroSessionResponse;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that concurrent retro session creation never produces two identical join codes, under
 * real contention against a real Testcontainers PostgreSQL instance so the {@code UNIQUE}
 * constraint on {@code agilite.retro_sessions.join_code} is genuinely enforced (US20.1.1 Gate-1
 * AC — concurrency test).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
class RetroSessionConcurrencyIT {

    private static final int CONCURRENT_CREATIONS = 64;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies container-derived connection properties and seeds the {@code public} schema
     * before the Spring context and its Flyway run start.
     *
     * @param registry the dynamic property registry
     */
    @DynamicPropertySource
    static void overrideProperties(final DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        PlatformAuthTestSupport.createPublicSchema(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Autowired
    private RetroSessionService sessionService;

    /**
     * Given many threads creating retro sessions for the same team at the same time, when all
     * of them complete, then every created session succeeded and every join code is unique —
     * proving the {@code UNIQUE} constraint plus bounded regeneration hold under real
     * concurrent writes, not just in isolation.
     *
     * @throws Exception if thread coordination or seeding fails
     */
    @Test
    void concurrentSessionCreation_neverProducesDuplicateJoinCodes() throws Exception {
        AuthFixture facilitator = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        long teamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                facilitator.tenantId(), "Concurrency Team");
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                teamId, facilitator.userId());

        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Callable<RetroSessionResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_CREATIONS; i++) {
            int index = i;
            tasks.add(() -> {
                startLatch.await();
                CreateRetroSessionRequest request = new CreateRetroSessionRequest(
                        "Concurrent Retro " + index, "START_STOP_CONTINUE", teamId,
                        null, null, null, null, null, null);
                return sessionService.create(request, facilitator.userId(), facilitator.tenantId());
            });
        }

        List<Future<RetroSessionResponse>> futures = new ArrayList<>();
        for (Callable<RetroSessionResponse> task : tasks) {
            futures.add(executor.submit(task));
        }
        startLatch.countDown();

        List<String> joinCodes = new ArrayList<>();
        for (Future<RetroSessionResponse> future : futures) {
            joinCodes.add(future.get().joinCode());
        }
        executor.shutdown();

        assertThat(joinCodes).hasSize(CONCURRENT_CREATIONS);
        Set<String> distinctCodes = joinCodes.stream().collect(Collectors.toSet());
        assertThat(distinctCodes).hasSize(CONCURRENT_CREATIONS);
    }
}
