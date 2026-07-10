package fr.pivot.agilite.wheel.ws;

import fr.pivot.agilite.wheel.WheelService;
import fr.pivot.agilite.ws.WsConnectionPrincipal;
import fr.pivot.agilite.ws.WsErrorPayload;
import fr.pivot.core.auth.AuthenticatedPrincipal;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WheelChannelInterceptor} — the US14.3.1 wheel subscription
 * authorization boundary.
 *
 * <p>End-to-end SUBSCRIBE authorization against a real transport, a real bearer token, and a
 * real {@code POST /spin}-triggered broadcast is covered separately by {@code
 * WheelWsIsolationIT}.
 */
class WheelChannelInterceptorTest {

    private static final UUID WHEEL_ID = UUID.randomUUID();
    private static final Long USER_ID = 100L;
    private static final Long TENANT_ID = 1L;
    private static final String VALID_TOKEN = "a-valid-bearer-token";
    private static final String SESSION_ID = "session-1";

    private AuthenticatedPrincipalResolver principalResolver;
    private WheelService wheelService;
    private SimpMessagingTemplate messagingTemplate;
    private WheelChannelInterceptor interceptor;

    /** Sets up a fresh interceptor with mocked collaborators before each test. */
    @BeforeEach
    void setUp() {
        principalResolver = mock(AuthenticatedPrincipalResolver.class);
        when(principalResolver.resolve(VALID_TOKEN))
                .thenReturn(Optional.of(new AuthenticatedPrincipal(USER_ID, TENANT_ID, "ROLE_USER")));

        wheelService = mock(WheelService.class);
        when(wheelService.isAccessibleTo(WHEEL_ID, USER_ID, TENANT_ID)).thenReturn(true);

        messagingTemplate = mock(SimpMessagingTemplate.class);

        interceptor = new WheelChannelInterceptor(principalResolver, wheelService);
        ReflectionTestUtils.setField(interceptor, "messagingTemplate", messagingTemplate);
    }

    /**
     * Given a valid bearer token whose caller is a member of the wheel's team, when a SUBSCRIBE
     * frame for that wheel's topic is processed, then it is allowed through unchanged.
     */
    @Test
    void subscribeWithValidTokenAndTeamMembershipIsAllowed() {
        Message<?> result =
                interceptor.preSend(buildSubscribeMessage(WHEEL_ID, VALID_TOKEN), mock(MessageChannel.class));

        assertThat(result).isNotNull();
    }

    /**
     * Given no {@code Authorization} header presented at all, when a SUBSCRIBE frame is
     * processed, then it is dropped and a generic error notification is sent to the session.
     */
    @Test
    void subscribeWithoutAuthorizationHeaderIsDenied() {
        Message<?> result = interceptor.preSend(buildSubscribeMessage(WHEEL_ID, null), mock(MessageChannel.class));

        assertThat(result).isNull();
        ArgumentCaptor<WsErrorPayload> payloadCaptor = ArgumentCaptor.forClass(WsErrorPayload.class);
        verify(messagingTemplate).convertAndSendToUser(anyString(), eq("/queue/errors"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().error()).contains("Access denied");
    }

    /**
     * Given a malformed {@code Authorization} header (missing the {@code Bearer } prefix), when
     * a SUBSCRIBE frame is processed, then it is denied exactly like a missing header.
     */
    @Test
    void subscribeWithMalformedAuthorizationHeaderIsDenied() {
        Message<?> message = buildRawMessage(
                StompCommand.SUBSCRIBE, WheelDestinations.wheelTopic(WHEEL_ID), "not-bearer-prefixed");

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isNull();
    }

    /**
     * Given a syntactically valid bearer token that does not resolve to any known principal
     * (unknown/expired/revoked), when a SUBSCRIBE frame is processed, then it is denied — same
     * generic denial as a missing header (anti-enumeration).
     */
    @Test
    void subscribeWithUnresolvableTokenIsDenied() {
        when(principalResolver.resolve("garbage-token")).thenReturn(Optional.empty());

        Message<?> result =
                interceptor.preSend(buildSubscribeMessage(WHEEL_ID, "garbage-token"), mock(MessageChannel.class));

        assertThat(result).isNull();
    }

    /**
     * Security AC: given a valid, resolvable bearer token whose owner is **not** a member of the
     * wheel's owning team (or the wheel does not exist/belongs to another tenant — {@link
     * WheelService#isAccessibleTo} collapses both), when a SUBSCRIBE frame is processed, then it
     * is denied with the exact same generic message as an invalid token — never distinguishable.
     */
    @Test
    void subscribeWithValidTokenButNoWheelAccessIsDenied() {
        when(wheelService.isAccessibleTo(WHEEL_ID, USER_ID, TENANT_ID)).thenReturn(false);

        Message<?> result =
                interceptor.preSend(buildSubscribeMessage(WHEEL_ID, VALID_TOKEN), mock(MessageChannel.class));

        assertThat(result).isNull();
        ArgumentCaptor<WsErrorPayload> payloadCaptor = ArgumentCaptor.forClass(WsErrorPayload.class);
        verify(messagingTemplate).convertAndSendToUser(anyString(), eq("/queue/errors"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().error()).contains("Access denied");
    }

    /**
     * Given a SUBSCRIBE destination whose wheel id segment is not a parseable UUID, then the
     * frame is dropped without any error notification (no valid wheel context to address).
     */
    @Test
    void subscribeWithUnparseableWheelIdIsDenied() {
        Message<?> message = buildRawMessage(
                StompCommand.SUBSCRIBE, WheelDestinations.TOPIC_WHEEL_PREFIX + "not-a-uuid", null);

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isNull();
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    /**
     * Given a SUBSCRIBE destination that has nothing to do with wheels, when it is processed,
     * then the interceptor passes it through completely unchanged — other domains sharing the
     * same {@code /ws/agilite} endpoint must never be affected by this interceptor.
     */
    @Test
    void subscribeToUnrelatedDestinationPassesThrough() {
        Message<?> message = buildRawMessage(StompCommand.SUBSCRIBE, "/topic/agilite/poker/" + WHEEL_ID, null);

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isSameAs(message);
    }

    /**
     * Given a SEND frame (any destination), when it is processed, then it always passes through
     * unchanged — no client-to-server wheel destination exists, so this interceptor never acts
     * on SEND at all (Gate 1 decision, see {@code us-diffusion-ws.md}).
     */
    @Test
    void sendFramesAlwaysPassThroughUnchanged() {
        Message<?> message = buildRawMessage(StompCommand.SEND, "/app/agilite/wheels/" + WHEEL_ID, VALID_TOKEN);

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isSameAs(message);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Builds a STOMP SUBSCRIBE frame targeting the given wheel's topic, presenting the given
     * bearer token (or none, if {@code null}) on the {@code Authorization} native header.
     *
     * @param wheelId the wheel to subscribe to
     * @param token   the raw bearer token to present (without the {@code Bearer } prefix), or
     *                {@code null} to omit the header entirely
     * @return the constructed STOMP SUBSCRIBE message
     */
    private Message<byte[]> buildSubscribeMessage(final UUID wheelId, final String token) {
        return buildRawMessage(
                StompCommand.SUBSCRIBE, WheelDestinations.wheelTopic(wheelId), token == null ? null : "Bearer " + token);
    }

    /**
     * Builds a raw STOMP frame with the given command, destination, and optional raw
     * {@code Authorization} native header value, always carrying a {@link WsConnectionPrincipal}
     * as the user.
     *
     * @param command             the STOMP command
     * @param destination         the destination header
     * @param authorizationHeader the raw {@code Authorization} native header value (including
     *                            any prefix), or {@code null} to omit it
     * @return the constructed message
     */
    private Message<byte[]> buildRawMessage(
            final StompCommand command, final String destination, final String authorizationHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(SESSION_ID);
        accessor.setDestination(destination);
        accessor.setUser(new WsConnectionPrincipal("connection-1"));
        if (authorizationHeader != null) {
            accessor.addNativeHeader(WheelChannelInterceptor.AUTHORIZATION_HEADER, authorizationHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
