package fr.pivot.agilite.retro.vote;

import fr.pivot.agilite.retro.card.RetroCard;
import fr.pivot.agilite.retro.card.RetroCardRepository;
import fr.pivot.agilite.retro.session.RetroFormat;
import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.session.RetroSession;
import fr.pivot.agilite.retro.session.RetroSessionRepository;
import fr.pivot.agilite.retro.ws.RetroSessionAccessService;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the US20.1.2b AC-mandated concurrency requirement — "2 participants votent
 * simultanément sur la même card jusqu'à épuisement de leur solde respectif — aucune race
 * condition sur le compteur global" — under real concurrent contention against a real
 * Testcontainers PostgreSQL instance, so {@link RetroVoteBalanceRepository}'s guarded atomic
 * {@code UPDATE}s (see its class JavaDoc) are genuinely exercised, not merely called sequentially.
 *
 * <p>Mirrors {@code RetroSessionConcurrencyIT}'s structure: {@code webEnvironment = NONE}, calling
 * {@link RetroVoteService#castVote} directly (no STOMP transport needed) from an {@link
 * ExecutorService}/{@link CountDownLatch}-coordinated pool, with a {@code null} {@link
 * java.security.Principal} — safe because every notification path in {@code RetroVoteService}
 * already no-ops on a {@code null} principal (mirrors {@code RetroCardService#notifyError}'s own
 * null-guard).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
class RetroVoteConcurrencyIT {

    /** Deliberately much larger than the balance, so most attempts must be rejected. */
    private static final int CONCURRENT_ATTEMPTS_PER_PARTICIPANT = 20;

    /** The session's configured {@code voteCountPerParticipant} for this test. */
    private static final int VOTES_ALLOWED = 3;

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
    private RetroVoteService voteService;

    @Autowired
    private RetroVoteRepository voteRepository;

    @Autowired
    private RetroVoteBalanceRepository balanceRepository;

    @Autowired
    private RetroSessionRepository sessionRepository;

    @Autowired
    private RetroCardRepository cardRepository;

    @Autowired
    private RetroSessionAccessService accessService;

    /**
     * Given two distinct participants each firing far more concurrent {@code castVote} calls than
     * their allotment on the very same card, when all of them complete, then: each participant's
     * balance never exceeds (or goes below zero of) their allotment, the card's total vote count
     * equals exactly the sum of both allotments (no lost vote, no double-count from the race), and
     * the number of rejected attempts per participant is exactly implied by their final balance
     * (20 total attempts − 3 successes = 17 rejections) — proving the guarded atomic {@code
     * UPDATE} in {@link RetroVoteBalanceRepository#incrementIfAvailable} holds under genuine
     * concurrent contention, not merely in isolation.
     *
     * @throws Exception if thread coordination or seeding fails
     */
    @Test
    void concurrentCastVote_neverExceedsBalance_andNeverLosesOrDoubleCountsVotes() throws Exception {
        AuthFixture facilitator = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        long teamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                facilitator.tenantId(), "Vote Concurrency Team");
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                teamId, facilitator.userId());

        Instant now = Instant.now();
        RetroSession session = new RetroSession(
                facilitator.tenantId(), teamId, "Vote Concurrency Retro", RetroFormat.START_STOP_CONTINUE,
                null, null, facilitator.userId(), "VOTE01", null, null, null, VOTES_ALLOWED,
                now.plusSeconds(3600), now);
        session.setCurrentPhase(RetroPhase.VOTE);
        session = sessionRepository.save(session);
        UUID sessionId = session.getId();

        RetroCard card = cardRepository.save(new RetroCard(
                sessionId, "went-well", "Hotly contested card", false, facilitator.userId(), now));
        UUID cardId = card.getId();

        String participant1Token = accessService.join(sessionId, null).accessToken();
        String participant2Token = accessService.join(sessionId, null).accessToken();

        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (String voterToken : List.of(participant1Token, participant2Token)) {
            for (int i = 0; i < CONCURRENT_ATTEMPTS_PER_PARTICIPANT; i++) {
                tasks.add(() -> {
                    startLatch.await();
                    voteService.castVote(sessionId, cardId, voterToken, null);
                    return null;
                });
            }
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(executor.submit(task));
        }
        startLatch.countDown();
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        RetroVoteBalance balance1 = balanceRepository.findBySessionIdAndVoterToken(sessionId, participant1Token)
                .orElseThrow();
        RetroVoteBalance balance2 = balanceRepository.findBySessionIdAndVoterToken(sessionId, participant2Token)
                .orElseThrow();

        assertThat(balance1.getVotesUsed()).isEqualTo(VOTES_ALLOWED);
        assertThat(balance2.getVotesUsed()).isEqualTo(VOTES_ALLOWED);
        assertThat(balance1.getVotesAllowed()).isEqualTo(VOTES_ALLOWED);
        assertThat(balance2.getVotesAllowed()).isEqualTo(VOTES_ALLOWED);

        int rejectedForParticipant1 = CONCURRENT_ATTEMPTS_PER_PARTICIPANT - balance1.getVotesUsed();
        int rejectedForParticipant2 = CONCURRENT_ATTEMPTS_PER_PARTICIPANT - balance2.getVotesUsed();
        assertThat(rejectedForParticipant1).isEqualTo(CONCURRENT_ATTEMPTS_PER_PARTICIPANT - VOTES_ALLOWED);
        assertThat(rejectedForParticipant2).isEqualTo(CONCURRENT_ATTEMPTS_PER_PARTICIPANT - VOTES_ALLOWED);

        long totalVotesOnCard = voteRepository.countByCardId(cardId);
        assertThat(totalVotesOnCard).isEqualTo((long) VOTES_ALLOWED * 2);
    }
}
