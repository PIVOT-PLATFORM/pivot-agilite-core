package fr.pivot.agilite.poker.ws;

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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * STOMP channel interceptor that enforces planning-poker room isolation on every inbound frame
 * (EN09.1).
 *
 * <p>Intercepts two STOMP commands, acting only on destinations under
 * {@link PokerRoomDestinations#TOPIC_ROOM_PREFIX} / {@link PokerRoomDestinations#APP_ROOM_PREFIX}
 * — any other destination (other domains sharing {@code /ws/agilite}, or STOMP infrastructure
 * frames) passes through completely unchanged:
 * <ul>
 *   <li><b>SUBSCRIBE</b> — verifies the caller presents a currently valid room access grant
 *       (native header {@value #ACCESS_TOKEN_HEADER}, checked via
 *       {@link RoomAccessGrantService#hasAccess}) before allowing a subscription to
 *       {@code /topic/agilite/poker/{roomId}}. Denied requests are dropped (the subscription is
 *       never established) and an error notification is sent to the session's own
 *       {@code /user/queue/errors}. The WebSocket session is <em>not</em> closed; other active
 *       subscriptions remain valid.</li>
 *   <li><b>SEND</b> — verifies the same grant for any message destined at
 *       {@code /app/agilite/poker/{roomId}/...} and enforces a rate limit of
 *       {@value #MAX_MESSAGES_PER_SECOND} messages per second per {@code (roomId, accessToken)}
 *       using a fixed-window Redis counter. Every rate-limited frame increments a per-session
 *       strike counter; on the {@value #MAX_STRIKES}th <em>consecutive</em> violation the
 *       session is force-closed via {@link WsSessionRegistry#close} (mirrors the EN08.1/
 *       US08.3.1 whiteboard precedent) — the closure notification is delivered to
 *       {@code /user/queue/errors} first, then the transport connection is terminated after a
 *       short grace delay so the notification has a chance to actually reach the client (see
 *       {@link #CLOSE_GRACE_DELAY_MILLIS}).</li>
 * </ul>
 *
 * <p><strong>No tenantId or userId is ever read from a client-supplied header, and none is
 * needed here</strong> — authorization is governed exclusively by the {@code (roomId,
 * accessToken)} grant pair (see {@link RoomAccessGrantService}'s JavaDoc for the full rationale).
 * There is no code path in this class that trusts a client's claim about which tenant or user it
 * is; a room's isolation from every other room (and, transitively, every other tenant's rooms)
 * is a property of the grant store, not of anything read off the wire here.
 *
 * <p>{@link SimpMessagingTemplate} is injected lazily to break the circular dependency that
 * would arise because this interceptor is registered on the client inbound channel during
 * Spring's message broker configuration phase, before the messaging template is fully
 * initialised.
 */
@Component
public class PokerChannelInterceptor implements ChannelInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(PokerChannelInterceptor.class);

    /** Native STOMP header carrying the room access grant token. */
    static final String ACCESS_TOKEN_HEADER = "access-token";

    /** Redis key prefix for per-room per-token rate-limit counters. */
    private static final String RATE_KEY_PREFIX = "poker:ws:rate:";

    /** Redis key prefix for per-session consecutive rate-limit violation strike counters. */
    private static final String STRIKES_KEY_PREFIX = "poker:ws:strikes:";

    /** Maximum allowed STOMP SEND frames per participant per room per second. */
    private static final int MAX_MESSAGES_PER_SECOND = 30;

    /** Number of consecutive rate-limit violations before effective session close. */
    private static final int MAX_STRIKES = 3;

    /** Window duration for the rate-limit counter (1 second fixed window). */
    private static final Duration RATE_WINDOW = Duration.ofSeconds(1);

    /** Strike counter TTL — resets automatically after 60 s of clean traffic. */
    private static final Duration STRIKES_TTL = Duration.ofSeconds(60);

    /**
     * Grace delay between delivering the closure notification and actually closing the
     * transport connection — see {@code WhiteboardChannelInterceptor} (EN08.1,
     * {@code pivot-collaboratif-core}) for the empirically-observed race this avoids:
     * {@link SimpMessagingTemplate#convertAndSendToUser} only enqueues the frame onto the async
     * {@code clientOutboundChannel}, it does not block until the frame is actually flushed to
     * the client's socket.
     */
    private static final long CLOSE_GRACE_DELAY_MILLIS = 250L;

    private final RoomAccessGrantService roomAccessGrantService;
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
     * @param roomAccessGrantService grant store used for SUBSCRIBE/SEND authorization decisions
     * @param redisTemplate          Redis client for rate-limit counters
     * @param meterRegistry          Micrometer registry for the throttled-messages counter
     * @param sessionRegistry        registry used to force-close a session after
     *                               {@value #MAX_STRIKES} consecutive rate-limit violations
     */
    public PokerChannelInterceptor(
            final RoomAccessGrantService roomAccessGrantService,
            final StringRedisTemplate redisTemplate,
            final MeterRegistry meterRegistry,
            final WsSessionRegistry sessionRegistry) {
        this.roomAccessGrantService = roomAccessGrantService;
        this.redisTemplate = redisTemplate;
        this.throttledCounter = Counter.builder("poker.messages.throttled.total")
                .description("Total STOMP planning-poker messages dropped due to rate limiting")
                .register(meterRegistry);
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Intercepts inbound STOMP frames, enforcing room access grants and rate limits.
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
     * Authorises a SUBSCRIBE frame targeting a room topic.
     *
     * <p>Destinations that do not start with {@link PokerRoomDestinations#TOPIC_ROOM_PREFIX} are
     * passed through unchanged. For room topics, the access grant is verified; if denied the
     * frame is dropped and an error notification is sent to the session.
     *
     * @param message  the SUBSCRIBE frame
     * @param accessor the mutable STOMP header accessor
     * @return the original message if allowed, {@code null} if denied
     */
    private Message<?> handleSubscribe(
            final Message<?> message, final StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(PokerRoomDestinations.TOPIC_ROOM_PREFIX)) {
            return message;
        }
        String roomIdStr =
                PokerRoomDestinations.extractRoomId(destination, PokerRoomDestinations.TOPIC_ROOM_PREFIX);
        if (roomIdStr == null) {
            return message;
        }
        UUID roomId = parseUuid(roomIdStr);
        if (roomId == null) {
            LOG.warn("SUBSCRIBE denied: unparseable roomId in destination={}", destination);
            return null;
        }
        String accessToken = accessor.getFirstNativeHeader(ACCESS_TOKEN_HEADER);
        if (!roomAccessGrantService.hasAccess(roomId, accessToken)) {
            LOG.warn("SUBSCRIBE denied: no valid access grant for room={}", roomId);
            sendError(accessor.getSessionId(), accessor.getUser(), "Access denied to room " + roomId);
            return null;
        }
        return message;
    }

    /**
     * Authorises a SEND frame targeting a room application destination and enforces the
     * per-participant rate limit.
     *
     * @param message  the SEND frame
     * @param accessor the mutable STOMP header accessor
     * @return the original message if allowed, {@code null} if denied or rate-limited
     */
    private Message<?> handleSend(
            final Message<?> message, final StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(PokerRoomDestinations.APP_ROOM_PREFIX)) {
            return message;
        }
        String roomIdStr =
                PokerRoomDestinations.extractRoomId(destination, PokerRoomDestinations.APP_ROOM_PREFIX);
        if (roomIdStr == null) {
            return message;
        }
        UUID roomId = parseUuid(roomIdStr);
        if (roomId == null) {
            LOG.warn("SEND denied: unparseable roomId in destination={}", destination);
            return null;
        }
        String accessToken = accessor.getFirstNativeHeader(ACCESS_TOKEN_HEADER);
        if (!roomAccessGrantService.hasAccess(roomId, accessToken)) {
            LOG.warn("SEND denied: no valid access grant for room={}", roomId);
            sendError(accessor.getSessionId(), accessor.getUser(), "Access denied to room " + roomId);
            return null;
        }
        String sessionId = accessor.getSessionId();
        if (isRateLimited(roomId, accessToken)) {
            throttledCounter.increment();
            LOG.warn("SEND rate-limited: room={}", roomId);
            long strikes = incrementStrikes(sessionId, roomId, accessToken);
            if (strikes >= MAX_STRIKES) {
                resetStrikes(sessionId, roomId, accessToken);
                sendError(accessor.getSessionId(), accessor.getUser(),
                        "Session closed after repeated rate limit violations — please reconnect");
                // Grace delay so the notification above actually reaches the client before the
                // transport is torn down — see CLOSE_GRACE_DELAY_MILLIS javadoc.
                CompletableFuture.delayedExecutor(CLOSE_GRACE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                        .execute(() -> sessionRegistry.close(sessionId, CloseStatus.POLICY_VIOLATION));
            } else {
                sendError(accessor.getSessionId(), accessor.getUser(),
                        "Rate limit exceeded (" + strikes + "/" + MAX_STRIKES + " violations)");
            }
            return null;
        }
        resetStrikes(sessionId, roomId, accessToken);
        return message;
    }

    /**
     * Checks whether the given participant has exceeded the per-second rate limit using a
     * fixed-window Redis INCR counter with a 1-second TTL.
     *
     * @param roomId      the room UUID
     * @param accessToken the participant's access token
     * @return {@code true} if the limit is exceeded; {@code false} otherwise
     */
    private boolean isRateLimited(final UUID roomId, final String accessToken) {
        String key = RATE_KEY_PREFIX + roomId + ":" + accessToken;
        Long count = redisTemplate.opsForValue().increment(key);
        if (Long.valueOf(1L).equals(count)) {
            redisTemplate.expire(key, RATE_WINDOW);
        }
        return count != null && count > MAX_MESSAGES_PER_SECOND;
    }

    /**
     * Increments the consecutive rate-limit violation strike counter for the session and
     * returns the current strike count.
     *
     * <p>The strike key has a 60-second TTL so that clean periods reset the counter
     * automatically. {@value #MAX_STRIKES} consecutive violations trigger effective session
     * close.
     *
     * @param sessionId   the STOMP session ID (used as part of the key)
     * @param roomId      the room UUID
     * @param accessToken the participant's access token
     * @return the current strike count after incrementing
     */
    private long incrementStrikes(final String sessionId, final UUID roomId, final String accessToken) {
        String key = STRIKES_KEY_PREFIX + sessionId + ":" + roomId + ":" + accessToken;
        Long count = redisTemplate.opsForValue().increment(key);
        if (Long.valueOf(1L).equals(count)) {
            redisTemplate.expire(key, STRIKES_TTL);
        }
        return count != null ? count : 1L;
    }

    /**
     * Resets the strike counter for the session after a successful (non-rate-limited) message.
     *
     * @param sessionId   the STOMP session ID
     * @param roomId      the room UUID
     * @param accessToken the participant's access token
     */
    private void resetStrikes(final String sessionId, final UUID roomId, final String accessToken) {
        if (sessionId == null) {
            return;
        }
        String key = STRIKES_KEY_PREFIX + sessionId + ":" + roomId + ":" + accessToken;
        redisTemplate.delete(key);
    }

    /**
     * Sends an error notification to the session's {@code /user/queue/errors} destination.
     * Failures to deliver the notification are logged at DEBUG and do not propagate.
     *
     * @param sessionId the STOMP session ID (for logging)
     * @param user      the session principal (for user-scoped routing)
     * @param error     the human-readable error reason
     */
    private void sendError(final String sessionId, final Principal user, final String error) {
        if (user == null || messagingTemplate == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(user.getName(), "/queue/errors", new WsErrorPayload(error));
        } catch (Exception e) {
            LOG.debug("Could not deliver error notification to session={}: {}", sessionId, e.getMessage());
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
