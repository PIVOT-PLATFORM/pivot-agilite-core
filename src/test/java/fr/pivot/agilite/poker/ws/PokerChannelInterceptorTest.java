package fr.pivot.agilite.poker.ws;

import fr.pivot.agilite.ws.WsConnectionPrincipal;
import fr.pivot.agilite.ws.WsErrorPayload;
import fr.pivot.agilite.ws.WsSessionRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PokerChannelInterceptor} — the EN09.1 room isolation and rate-limit
 * enforcement.
 *
 * <p>Redis is mocked with a real-INCR-like stub (a plain counter per key, distinguishing the
 * rate-limit key prefix from the strike key prefix) so the strike/consecutive-violation state
 * machine can be driven precisely and deterministically. The corresponding end-to-end behavior
 * against a real Redis instance and a real WebSocket transport is covered by
 * {@code PokerRateLimitEnforcementIT}; SUBSCRIBE/SEND authorization against a real grant store
 * and real transport is covered by {@code PokerRoomIsolationIT}.
 */
class PokerChannelInterceptorTest {

    private static final UUID ROOM_ID = UUID.randomUUID();
    private static final UUID OTHER_ROOM_ID = UUID.randomUUID();
    private static final String ACCESS_TOKEN = "grant-token-1";
    private static final String SESSION_ID = "session-1";
    private static final String SEND_DESTINATION = PokerRoomDestinations.APP_ROOM_PREFIX + ROOM_ID + "/vote";
    private static final String TOPIC_DESTINATION = PokerRoomDestinations.roomTopic(ROOM_ID);
    private static final String STRIKES_KEY =
            "poker:ws:strikes:" + SESSION_ID + ":" + ROOM_ID + ":" + ACCESS_TOKEN;

    private RoomAccessGrantService roomAccessGrantService;
    private StringRedisTemplate redisTemplate;
    private WsSessionRegistry sessionRegistry;
    private SimpMessagingTemplate messagingTemplate;
    private PokerChannelInterceptor interceptor;

    /** Fixed-window rate counter for the single (room, token) pair under test. */
    private AtomicLong rateCounter;

    /** Sets up a fresh interceptor with mocked collaborators before each test. */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        roomAccessGrantService = mock(RoomAccessGrantService.class);
        when(roomAccessGrantService.hasAccess(ROOM_ID, ACCESS_TOKEN)).thenReturn(true);

        redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        rateCounter = new AtomicLong(0);
        AtomicLong strikeCounter = new AtomicLong(0);
        when(valueOperations.increment(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key.startsWith("poker:ws:rate:")) {
                return rateCounter.incrementAndGet();
            }
            return strikeCounter.incrementAndGet();
        });
        when(redisTemplate.delete(STRIKES_KEY)).thenAnswer(invocation -> {
            strikeCounter.set(0);
            return true;
        });

        sessionRegistry = mock(WsSessionRegistry.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);

        interceptor = new PokerChannelInterceptor(
                roomAccessGrantService, redisTemplate, new SimpleMeterRegistry(), sessionRegistry);
        ReflectionTestUtils.setField(interceptor, "messagingTemplate", messagingTemplate);
    }

    // =========================================================================
    // SUBSCRIBE authorization
    // =========================================================================

    /**
     * Given a valid room access grant, when a SUBSCRIBE frame for that room is processed, then
     * it is allowed through unchanged.
     */
    @Test
    void subscribeWithValidGrantIsAllowed() {
        Message<?> result = interceptor.preSend(buildSubscribeMessage(ROOM_ID, ACCESS_TOKEN), mock(MessageChannel.class));

        assertThat(result).isNotNull();
    }

    /**
     * Given no access token presented at all, when a SUBSCRIBE frame for a room is processed,
     * then it is dropped and an error notification is sent to the session.
     */
    @Test
    void subscribeWithoutAccessTokenIsDenied() {
        Message<?> result = interceptor.preSend(buildSubscribeMessage(ROOM_ID, null), mock(MessageChannel.class));

        assertThat(result).isNull();
        ArgumentCaptor<WsErrorPayload> payloadCaptor = ArgumentCaptor.forClass(WsErrorPayload.class);
        verify(messagingTemplate).convertAndSendToUser(anyString(), eq("/queue/errors"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().error()).contains("Access denied");
    }

    /**
     * Security AC (cross-room isolation): given an access token granted for one room, when a
     * SUBSCRIBE frame targets a *different* room using that same token, then it is denied — a
     * grant never authorizes any room other than the one it was issued for.
     */
    @Test
    void subscribeWithTokenValidForAnotherRoomIsDenied() {
        Message<?> result =
                interceptor.preSend(buildSubscribeMessage(OTHER_ROOM_ID, ACCESS_TOKEN), mock(MessageChannel.class));

        assertThat(result).isNull();
    }

    /**
     * Given a SUBSCRIBE destination whose room id segment is not a parseable UUID, then the
     * frame is dropped without error (no room context to address).
     */
    @Test
    void subscribeWithUnparseableRoomIdIsDenied() {
        Message<?> message = buildRawMessage(StompCommand.SUBSCRIBE,
                PokerRoomDestinations.TOPIC_ROOM_PREFIX + "not-a-uuid", null, null);

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isNull();
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    /**
     * Given a SUBSCRIBE destination that has nothing to do with planning-poker rooms, when it is
     * processed, then the interceptor passes it through completely unchanged — other domains
     * sharing the same {@code /ws/agilite} endpoint must never be affected by this interceptor.
     */
    @Test
    void subscribeToUnrelatedDestinationPassesThrough() {
        Message<?> message = buildRawMessage(StompCommand.SUBSCRIBE, "/topic/agilite.capacity-updated", null, null);

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isSameAs(message);
    }

    // =========================================================================
    // SEND authorization
    // =========================================================================

    /**
     * Given no valid access grant, when a SEND frame targeting a room's application destination
     * is processed, then it is dropped and an error notification is sent.
     */
    @Test
    void sendWithoutValidGrantIsDenied() {
        Message<?> result = interceptor.preSend(buildSendMessage(null), mock(MessageChannel.class));

        assertThat(result).isNull();
        verify(messagingTemplate).convertAndSendToUser(anyString(), eq("/queue/errors"), any(WsErrorPayload.class));
    }

    /**
     * Given a SEND destination outside the poker application prefix, then the frame passes
     * through unchanged.
     */
    @Test
    void sendToUnrelatedDestinationPassesThrough() {
        Message<?> message = buildRawMessage(StompCommand.SEND, "/app/agilite/capacity/update", null, null);

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isSameAs(message);
    }

    // =========================================================================
    // Rate limiting / strikes
    // =========================================================================

    /**
     * Given a participant who exceeds the per-second SEND rate limit on three consecutive
     * frames, when the third violation is processed, then the session is force-closed via
     * {@link WsSessionRegistry#close} with {@link CloseStatus#POLICY_VIOLATION}.
     */
    @Test
    void thirdConsecutiveViolationForceClosesTheSession() {
        primeRateLimitOverThreshold();

        sendFrame();
        sendFrame();
        verify(sessionRegistry, never()).close(anyString(), any());

        sendFrame();

        verify(sessionRegistry, timeout(1000).times(1))
                .close(SESSION_ID, CloseStatus.POLICY_VIOLATION);
    }

    /**
     * Given the first and second consecutive violations, when each is processed, then the
     * client is warned with the running strike count rather than being told the session was
     * closed, and the session is not actually closed.
     */
    @Test
    void firstAndSecondViolationsWarnWithoutClosing() {
        primeRateLimitOverThreshold();

        sendFrame();
        sendFrame();

        ArgumentCaptor<WsErrorPayload> payloadCaptor = ArgumentCaptor.forClass(WsErrorPayload.class);
        verify(messagingTemplate, times(2))
                .convertAndSendToUser(anyString(), eq("/queue/errors"), payloadCaptor.capture());
        assertThat(payloadCaptor.getAllValues().get(0).error()).contains("1/3");
        assertThat(payloadCaptor.getAllValues().get(1).error()).contains("2/3");
        verify(sessionRegistry, never()).close(anyString(), any());
    }

    /**
     * Given one rate-limit violation followed by a normal, within-limit message, when the
     * within-limit message is processed, then the strike counter is reset so that two further
     * consecutive violations afterward do NOT reach the third-strike threshold.
     */
    @Test
    void acceptedMessageBetweenViolationsResetsTheStrikeCounter() {
        primeRateLimitOverThreshold();
        sendFrame();
        verify(redisTemplate, never()).delete(STRIKES_KEY);

        rateCounter.set(0);
        sendFrame();
        verify(redisTemplate, times(1)).delete(STRIKES_KEY);

        rateCounter.set(30);
        sendFrame();
        sendFrame();
        verify(sessionRegistry, never()).close(anyString(), any());
    }

    /**
     * Given a rate-limited SEND frame, when it is processed, then the
     * {@code poker.messages.throttled.total} Micrometer counter is incremented.
     */
    @Test
    void rateLimitedFrameIncrementsThrottledCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PokerChannelInterceptor withRealRegistry = new PokerChannelInterceptor(
                roomAccessGrantService, redisTemplate, registry, sessionRegistry);
        ReflectionTestUtils.setField(withRealRegistry, "messagingTemplate", messagingTemplate);
        rateCounter.set(30);

        withRealRegistry.preSend(buildSendMessage(ACCESS_TOKEN), mock(MessageChannel.class));

        assertThat(registry.get("poker.messages.throttled.total").counter().count()).isEqualTo(1.0d);
    }

    /**
     * Given a SEND frame within the rate limit, when it is processed, then the message passes
     * through unchanged (not dropped).
     */
    @Test
    void withinLimitFrameIsAllowedThrough() {
        Message<?> result = interceptor.preSend(buildSendMessage(ACCESS_TOKEN), mock(MessageChannel.class));

        assertThat(result).isNotNull();
        verify(sessionRegistry, never()).close(anyString(), any());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Sets the mocked rate counter so that the very next SEND on this (room, token) pair is
     * already over the {@code MAX_MESSAGES_PER_SECOND} limit.
     */
    private void primeRateLimitOverThreshold() {
        rateCounter.set(30);
    }

    private void sendFrame() {
        interceptor.preSend(buildSendMessage(ACCESS_TOKEN), mock(MessageChannel.class));
    }

    /**
     * Builds a STOMP SEND frame targeting the test room's vote action destination, presenting
     * the given access token (or none, if {@code null}).
     *
     * @param accessToken the access token to present as a native header, or {@code null}
     * @return the constructed STOMP SEND message
     */
    private Message<byte[]> buildSendMessage(final String accessToken) {
        return buildRawMessage(StompCommand.SEND, SEND_DESTINATION, SESSION_ID, accessToken);
    }

    /**
     * Builds a STOMP SUBSCRIBE frame targeting the given room's topic, presenting the given
     * access token (or none, if {@code null}).
     *
     * @param roomId      the room to subscribe to
     * @param accessToken the access token to present as a native header, or {@code null}
     * @return the constructed STOMP SUBSCRIBE message
     */
    private Message<byte[]> buildSubscribeMessage(final UUID roomId, final String accessToken) {
        return buildRawMessage(
                StompCommand.SUBSCRIBE, PokerRoomDestinations.roomTopic(roomId), SESSION_ID, accessToken);
    }

    /**
     * Builds a raw STOMP frame with the given command, destination, session id, and optional
     * access token native header, always carrying a {@link WsConnectionPrincipal} as the user.
     *
     * @param command     the STOMP command
     * @param destination the destination header
     * @param sessionId   the session id, or {@code null}
     * @param accessToken the access token native header value, or {@code null} to omit it
     * @return the constructed message
     */
    private Message<byte[]> buildRawMessage(
            final StompCommand command, final String destination, final String sessionId, final String accessToken) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId != null ? sessionId : SESSION_ID);
        accessor.setDestination(destination);
        accessor.setUser(new WsConnectionPrincipal("connection-1"));
        if (accessToken != null) {
            accessor.addNativeHeader(PokerChannelInterceptor.ACCESS_TOKEN_HEADER, accessToken);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
