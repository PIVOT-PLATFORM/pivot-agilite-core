package fr.pivot.agilite.retro.ws;

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

import java.util.Optional;
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
 * Unit tests for {@link RetroChannelInterceptor} — the US20.1.2a retro session isolation and
 * rate-limit enforcement, adapted from {@code PokerChannelInterceptorTest} (EN09.1).
 *
 * <p>End-to-end proof against a real transport, real Redis, and — crucially — a raw STOMP frame
 * inspection proving no card content ever transits to a non-facilitator before {@code
 * CARDS_REVEALED} is covered by {@code RetroCardSubmissionIT}.
 */
class RetroChannelInterceptorTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID OTHER_SESSION_ID = UUID.randomUUID();
    private static final String ACCESS_TOKEN = "grant-token-1";
    private static final String WS_SESSION_ID = "session-1";
    private static final String SEND_DESTINATION = RetroSessionDestinations.APP_ROOM_PREFIX + SESSION_ID + "/cards";
    private static final String STRIKES_KEY =
            "retro:ws:strikes:" + WS_SESSION_ID + ":" + SESSION_ID + ":" + ACCESS_TOKEN;

    private RetroAccessGrantService retroAccessGrantService;
    private StringRedisTemplate redisTemplate;
    private WsSessionRegistry sessionRegistry;
    private SimpMessagingTemplate messagingTemplate;
    private RetroChannelInterceptor interceptor;

    private AtomicLong rateCounter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        retroAccessGrantService = mock(RetroAccessGrantService.class);
        RetroParticipantGrant participant = new RetroParticipantGrant(1L, 2L, false);
        when(retroAccessGrantService.resolveGrant(SESSION_ID, ACCESS_TOKEN)).thenReturn(Optional.of(participant));
        when(retroAccessGrantService.hasAccess(SESSION_ID, ACCESS_TOKEN)).thenReturn(true);

        redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        rateCounter = new AtomicLong(0);
        AtomicLong strikeCounter = new AtomicLong(0);
        when(valueOperations.increment(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key.startsWith("retro:ws:rate:")) {
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

        interceptor = new RetroChannelInterceptor(
                retroAccessGrantService, redisTemplate, new SimpleMeterRegistry(), sessionRegistry);
        ReflectionTestUtils.setField(interceptor, "messagingTemplate", messagingTemplate);
    }

    // =========================================================================
    // SUBSCRIBE authorization — regular (all-participants) topic
    // =========================================================================

    @Test
    void subscribeToRegularTopicWithValidGrantIsAllowed() {
        Message<?> result = interceptor.preSend(
                buildSubscribeMessage(RetroSessionDestinations.roomTopic(SESSION_ID), ACCESS_TOKEN), mock(MessageChannel.class));

        assertThat(result).isNotNull();
    }

    @Test
    void subscribeWithoutAccessTokenIsDenied() {
        Message<?> result = interceptor.preSend(
                buildSubscribeMessage(RetroSessionDestinations.roomTopic(SESSION_ID), null), mock(MessageChannel.class));

        assertThat(result).isNull();
        ArgumentCaptor<WsErrorPayload> payloadCaptor = ArgumentCaptor.forClass(WsErrorPayload.class);
        verify(messagingTemplate).convertAndSendToUser(anyString(), eq("/queue/errors"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().error()).contains("Access denied");
    }

    /** Cross-session isolation: a grant issued for one session never authorizes another. */
    @Test
    void subscribeWithTokenValidForAnotherSessionIsDenied() {
        Message<?> result = interceptor.preSend(
                buildSubscribeMessage(RetroSessionDestinations.roomTopic(OTHER_SESSION_ID), ACCESS_TOKEN),
                mock(MessageChannel.class));

        assertThat(result).isNull();
    }

    @Test
    void subscribeToUnrelatedDestinationPassesThrough() {
        Message<?> message = buildRawMessage(StompCommand.SUBSCRIBE, "/topic/agilite.capacity-updated", null, null);

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isSameAs(message);
    }

    @Test
    void subscribeWithUnparseableSessionIdIsDenied() {
        Message<?> message = buildRawMessage(
                StompCommand.SUBSCRIBE, RetroSessionDestinations.TOPIC_ROOM_PREFIX + "not-a-uuid", null, null);

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isNull();
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    // =========================================================================
    // SUBSCRIBE authorization — facilitator-only topic
    // =========================================================================

    /**
     * AC: a non-facilitator grant may never subscribe to the facilitator-only preview topic —
     * "aucun participant autre que l'animateur" sees content in clear before reveal.
     */
    @Test
    void subscribeToFacilitatorTopicWithNonFacilitatorGrantIsDenied() {
        Message<?> result = interceptor.preSend(
                buildSubscribeMessage(RetroSessionDestinations.facilitatorTopic(SESSION_ID), ACCESS_TOKEN),
                mock(MessageChannel.class));

        assertThat(result).isNull();
        ArgumentCaptor<WsErrorPayload> payloadCaptor = ArgumentCaptor.forClass(WsErrorPayload.class);
        verify(messagingTemplate).convertAndSendToUser(anyString(), eq("/queue/errors"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().error()).contains("facilitator");
    }

    /** A facilitator-flagged grant is allowed onto the facilitator-only preview topic. */
    @Test
    void subscribeToFacilitatorTopicWithFacilitatorGrantIsAllowed() {
        String facilitatorToken = "facilitator-token";
        when(retroAccessGrantService.resolveGrant(SESSION_ID, facilitatorToken))
                .thenReturn(Optional.of(new RetroParticipantGrant(1L, 2L, true)));

        Message<?> result = interceptor.preSend(
                buildSubscribeMessage(RetroSessionDestinations.facilitatorTopic(SESSION_ID), facilitatorToken),
                mock(MessageChannel.class));

        assertThat(result).isNotNull();
    }

    // =========================================================================
    // SEND authorization
    // =========================================================================

    @Test
    void sendWithoutValidGrantIsDenied() {
        Message<?> result = interceptor.preSend(buildSendMessage(null), mock(MessageChannel.class));

        assertThat(result).isNull();
        verify(messagingTemplate).convertAndSendToUser(anyString(), eq("/queue/errors"), any(WsErrorPayload.class));
    }

    @Test
    void sendToUnrelatedDestinationPassesThrough() {
        Message<?> message = buildRawMessage(StompCommand.SEND, "/app/agilite/capacity/update", null, null);

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isSameAs(message);
    }

    @Test
    void withinLimitFrameIsAllowedThrough() {
        Message<?> result = interceptor.preSend(buildSendMessage(ACCESS_TOKEN), mock(MessageChannel.class));

        assertThat(result).isNotNull();
        verify(sessionRegistry, never()).close(anyString(), any());
    }

    // =========================================================================
    // Rate limiting / strikes
    // =========================================================================

    @Test
    void thirdConsecutiveViolationForceClosesTheSession() {
        primeRateLimitOverThreshold();

        sendFrame();
        sendFrame();
        verify(sessionRegistry, never()).close(anyString(), any());

        sendFrame();

        verify(sessionRegistry, timeout(1000).times(1))
                .close(WS_SESSION_ID, CloseStatus.POLICY_VIOLATION);
    }

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

    @Test
    void rateLimitedFrameIncrementsThrottledCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetroChannelInterceptor withRealRegistry = new RetroChannelInterceptor(
                retroAccessGrantService, redisTemplate, registry, sessionRegistry);
        ReflectionTestUtils.setField(withRealRegistry, "messagingTemplate", messagingTemplate);
        rateCounter.set(30);

        withRealRegistry.preSend(buildSendMessage(ACCESS_TOKEN), mock(MessageChannel.class));

        assertThat(registry.get("retro.messages.throttled.total").counter().count()).isEqualTo(1.0d);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void primeRateLimitOverThreshold() {
        rateCounter.set(30);
    }

    private void sendFrame() {
        interceptor.preSend(buildSendMessage(ACCESS_TOKEN), mock(MessageChannel.class));
    }

    private Message<byte[]> buildSendMessage(final String accessToken) {
        return buildRawMessage(StompCommand.SEND, SEND_DESTINATION, WS_SESSION_ID, accessToken);
    }

    private Message<byte[]> buildSubscribeMessage(final String destination, final String accessToken) {
        return buildRawMessage(StompCommand.SUBSCRIBE, destination, WS_SESSION_ID, accessToken);
    }

    private Message<byte[]> buildRawMessage(
            final StompCommand command, final String destination, final String sessionId, final String accessToken) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId != null ? sessionId : WS_SESSION_ID);
        accessor.setDestination(destination);
        accessor.setUser(new WsConnectionPrincipal("connection-1"));
        if (accessToken != null) {
            accessor.addNativeHeader(RetroChannelInterceptor.ACCESS_TOKEN_HEADER, accessToken);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
