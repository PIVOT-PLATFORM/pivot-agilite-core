package fr.pivot.agilite.retro.ws;

import fr.pivot.agilite.ws.WsErrorPayload;
import fr.pivot.agilite.ws.WsSessionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.security.Principal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * STOMP channel interceptor that enforces retrospective session isolation on every inbound frame
 * (US20.1.2a).
 *
 * <p><strong>Adapts the EN09.1 {@code PokerChannelInterceptor} precedent</strong> — same access-
 * grant-gated SUBSCRIBE/SEND enforcement and per-participant Redis rate limiting with consecutive-
 * strike force-close — rather than inventing a new authorization mechanism for this domain. Two
 * differences, both driven by this US's AC (never required for planning poker):
 * <ul>
 *   <li>A grant here resolves to a full {@link RetroParticipantGrant} identity, not just a
 *       boolean — needed downstream (by {@code RetroCardService}) to attribute non-anonymous
 *       cards and to know who the facilitator is. This interceptor itself only ever inspects
 *       {@link RetroParticipantGrant#facilitator()}, for the one authorization decision below
 *       that depends on it.</li>
 *   <li>SUBSCRIBE additionally distinguishes the facilitator-only preview topic ({@link
 *       RetroSessionDestinations#facilitatorTopic(UUID)}) from the regular, masked, all-
 *       participants topic — a non-facilitator grant may subscribe to the former, never the
 *       latter (AC: "aucun participant autre que l'animateur" sees card content in clear before
 *       {@code CARDS_REVEALED}).</li>
 * </ul>
 *
 * <p>Card submission itself does not need the resolved identity to be threaded through this
 * interceptor into the {@code @MessageMapping} handler: the handler re-resolves the same grant
 * directly from the {@value #ACCESS_TOKEN_HEADER} native header via {@code
 * RetroAccessGrantService} (a second, cheap Redis read) rather than this interceptor mutating the
 * message to smuggle the grant through — avoids depending on {@link StompHeaderAccessor}
 * mutability guarantees for a message that has already been built by the time {@link #preSend}
 * runs.
 *
 * <p>See {@code PokerChannelInterceptor}'s JavaDoc for the full rationale behind the rate-limit/
 * strike/force-close mechanics reused verbatim here (only the Redis key prefixes and the
 * collaborator types differ).
 */
@Component
public class RetroChannelInterceptor implements ChannelInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(RetroChannelInterceptor.class);

    /**
     * Native STOMP header carrying the session access grant token. Public (unlike the poker
     * precedent's package-private equivalent): {@code RetroCardWsController} — in a different
     * package — needs the exact same literal to read the header for card-submission identity
     * resolution, and referencing this constant avoids duplicating it as a raw string.
     */
    public static final String ACCESS_TOKEN_HEADER = "access-token";

    /** Redis key prefix for per-session per-token rate-limit counters. */
    private static final String RATE_KEY_PREFIX = "retro:ws:rate:";

    /** Redis key prefix for per-session consecutive rate-limit violation strike counters. */
    private static final String STRIKES_KEY_PREFIX = "retro:ws:strikes:";

    /** Maximum allowed STOMP SEND frames per participant per session per second. */
    private static final int MAX_MESSAGES_PER_SECOND = 30;

    /** Number of consecutive rate-limit violations before effective session close. */
    private static final int MAX_STRIKES = 3;

    /** Window duration for the rate-limit counter (1 second fixed window). */
    private static final Duration RATE_WINDOW = Duration.ofSeconds(1);

    /** Strike counter TTL — resets automatically after 60 s of clean traffic. */
    private static final Duration STRIKES_TTL = Duration.ofSeconds(60);

    /** Grace delay between the closure notification and actually closing the transport. */
    private static final long CLOSE_GRACE_DELAY_MILLIS = 250L;

    private final RetroAccessGrantService retroAccessGrantService;
    private final StringRedisTemplate redisTemplate;
    private final Counter throttledCounter;
    private final WsSessionRegistry sessionRegistry;

    /**
     * Messaging template used to deliver error notifications to denied sessions.
     * Lazily injected to avoid a circular dependency during broker configuration.
     */
    @Lazy
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Creates the interceptor with the required dependencies.
     *
     * @param retroAccessGrantService grant store used for SUBSCRIBE/SEND authorization decisions
     * @param redisTemplate           Redis client for rate-limit counters
     * @param meterRegistry           Micrometer registry for the throttled-messages counter
     * @param sessionRegistry         registry used to force-close a session after {@value
     *                                #MAX_STRIKES} consecutive rate-limit violations
     */
    public RetroChannelInterceptor(
            final RetroAccessGrantService retroAccessGrantService,
            final StringRedisTemplate redisTemplate,
            final MeterRegistry meterRegistry,
            final WsSessionRegistry sessionRegistry) {
        this.retroAccessGrantService = retroAccessGrantService;
        this.redisTemplate = redisTemplate;
        this.throttledCounter = Counter.builder("retro.messages.throttled.total")
                .description("Total STOMP retro-session messages dropped due to rate limiting")
                .register(meterRegistry);
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Intercepts inbound STOMP frames, enforcing session access grants and rate limits.
     *
     * @param message the inbound STOMP message
     * @param channel the inbound channel
     * @return the message if the frame is allowed, {@code null} to silently drop it
     */
    @Override
    public Message<?> preSend(final Message<?> message, final MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }
        return switch (command) {
            case SUBSCRIBE -> handleSubscribe(message, accessor);
            case SEND -> handleSend(message, accessor);
            default -> message;
        };
    }

    /**
     * Authorises a SUBSCRIBE frame targeting a session topic (regular or facilitator-only).
     *
     * @param message  the SUBSCRIBE frame
     * @param accessor the STOMP header accessor
     * @return the original message if allowed, {@code null} if denied
     */
    private Message<?> handleSubscribe(final Message<?> message, final StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(RetroSessionDestinations.TOPIC_ROOM_PREFIX)) {
            return message;
        }
        String sessionIdStr = RetroSessionDestinations.extractSessionId(
                destination, RetroSessionDestinations.TOPIC_ROOM_PREFIX);
        if (sessionIdStr == null) {
            return message;
        }
        UUID sessionId = parseUuid(sessionIdStr);
        if (sessionId == null) {
            LOG.warn("SUBSCRIBE denied: unparseable sessionId in destination={}", destination);
            return null;
        }
        String accessToken = accessor.getFirstNativeHeader(ACCESS_TOKEN_HEADER);
        Optional<RetroParticipantGrant> grant = retroAccessGrantService.resolveGrant(sessionId, accessToken);
        if (grant.isEmpty()) {
            LOG.warn("SUBSCRIBE denied: no valid access grant for session={}", sessionId);
            sendError(accessor.getUser(), "Access denied to retro session " + sessionId);
            return null;
        }
        boolean facilitatorTopic = RetroSessionDestinations.isFacilitatorTopic(destination, sessionId);
        if (facilitatorTopic && !grant.get().facilitator()) {
            LOG.warn("SUBSCRIBE denied: non-facilitator attempted facilitator topic for session={}", sessionId);
            sendError(accessor.getUser(), "Only the facilitator may subscribe to this destination");
            return null;
        }
        return message;
    }

    /**
     * Authorises a SEND frame targeting a session application destination and enforces the
     * per-participant rate limit.
     *
     * @param message  the SEND frame
     * @param accessor the STOMP header accessor
     * @return the original message if allowed, {@code null} if denied or rate-limited
     */
    private Message<?> handleSend(final Message<?> message, final StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(RetroSessionDestinations.APP_ROOM_PREFIX)) {
            return message;
        }
        String sessionIdStr = RetroSessionDestinations.extractSessionId(
                destination, RetroSessionDestinations.APP_ROOM_PREFIX);
        if (sessionIdStr == null) {
            return message;
        }
        UUID sessionId = parseUuid(sessionIdStr);
        if (sessionId == null) {
            LOG.warn("SEND denied: unparseable sessionId in destination={}", destination);
            return null;
        }
        String accessToken = accessor.getFirstNativeHeader(ACCESS_TOKEN_HEADER);
        if (!retroAccessGrantService.hasAccess(sessionId, accessToken)) {
            LOG.warn("SEND denied: no valid access grant for session={}", sessionId);
            sendError(accessor.getUser(), "Access denied to retro session " + sessionId);
            return null;
        }
        String sessionId0 = accessor.getSessionId();
        if (isRateLimited(sessionId, accessToken)) {
            throttledCounter.increment();
            LOG.warn("SEND rate-limited: session={}", sessionId);
            long strikes = incrementStrikes(sessionId0, sessionId, accessToken);
            if (strikes >= MAX_STRIKES) {
                resetStrikes(sessionId0, sessionId, accessToken);
                sendError(accessor.getUser(),
                        "Session closed after repeated rate limit violations — please reconnect");
                CompletableFuture.delayedExecutor(CLOSE_GRACE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                        .execute(() -> sessionRegistry.close(sessionId0, CloseStatus.POLICY_VIOLATION));
            } else {
                sendError(accessor.getUser(),
                        "Rate limit exceeded (" + strikes + "/" + MAX_STRIKES + " violations)");
            }
            return null;
        }
        resetStrikes(sessionId0, sessionId, accessToken);
        return message;
    }

    /**
     * Checks whether the given participant has exceeded the per-second rate limit using a
     * fixed-window Redis INCR counter with a 1-second TTL.
     *
     * @param sessionId   the retro session UUID
     * @param accessToken the participant's access token
     * @return {@code true} if the limit is exceeded; {@code false} otherwise
     */
    private boolean isRateLimited(final UUID sessionId, final String accessToken) {
        String key = RATE_KEY_PREFIX + sessionId + ":" + accessToken;
        Long count = redisTemplate.opsForValue().increment(key);
        if (Long.valueOf(1L).equals(count)) {
            redisTemplate.expire(key, RATE_WINDOW);
        }
        return count != null && count > MAX_MESSAGES_PER_SECOND;
    }

    /**
     * Increments the consecutive rate-limit violation strike counter and returns the current
     * count.
     *
     * @param wsSessionId the STOMP session ID (used as part of the key)
     * @param sessionId   the retro session UUID
     * @param accessToken the participant's access token
     * @return the current strike count after incrementing
     */
    private long incrementStrikes(final String wsSessionId, final UUID sessionId, final String accessToken) {
        String key = STRIKES_KEY_PREFIX + wsSessionId + ":" + sessionId + ":" + accessToken;
        Long count = redisTemplate.opsForValue().increment(key);
        if (Long.valueOf(1L).equals(count)) {
            redisTemplate.expire(key, STRIKES_TTL);
        }
        return count != null ? count : 1L;
    }

    /**
     * Resets the strike counter for the session after a successful (non-rate-limited) message.
     *
     * @param wsSessionId the STOMP session ID
     * @param sessionId   the retro session UUID
     * @param accessToken the participant's access token
     */
    private void resetStrikes(final String wsSessionId, final UUID sessionId, final String accessToken) {
        if (wsSessionId == null) {
            return;
        }
        String key = STRIKES_KEY_PREFIX + wsSessionId + ":" + sessionId + ":" + accessToken;
        redisTemplate.delete(key);
    }

    /**
     * Sends an error notification to the session's {@code /user/queue/errors} destination.
     *
     * @param user  the session principal (for user-scoped routing)
     * @param error the human-readable error reason
     */
    private void sendError(final Principal user, final String error) {
        if (user == null || messagingTemplate == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(user.getName(), "/queue/errors", new WsErrorPayload(error));
        } catch (Exception e) {
            LOG.debug("Could not deliver error notification: {}", e.getMessage());
        }
    }

    /**
     * Parses a UUID string, returning {@code null} on any parse failure.
     *
     * @param value the raw UUID string
     * @return the parsed {@link UUID} or {@code null}
     */
    private UUID parseUuid(final String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
