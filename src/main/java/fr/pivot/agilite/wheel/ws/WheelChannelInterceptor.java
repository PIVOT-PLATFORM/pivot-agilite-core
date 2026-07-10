package fr.pivot.agilite.wheel.ws;

import fr.pivot.agilite.wheel.WheelService;
import fr.pivot.agilite.ws.WsErrorPayload;
import fr.pivot.core.auth.AuthenticatedPrincipal;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

/**
 * STOMP channel interceptor that enforces wheel subscription isolation on every inbound frame
 * (US14.3.1).
 *
 * <p><strong>Adapts the EN09.1 {@code PokerChannelInterceptor}/US20.1.2a {@code
 * RetroChannelInterceptor} precedent</strong> — same "only ever act on this domain's own
 * destination prefix, deny by silently dropping the frame plus a {@code /user/queue/errors}
 * notification, never close the session" shape — but with a **different authorization
 * mechanism**, deliberately: a wheel is scoped to a permanent team (exactly like its REST
 * endpoints, {@code WheelController}), not an ad hoc session joined via an invite code/link like
 * a planning-poker room or a retrospective session. There is therefore no opaque, server-minted
 * access grant to check here (no {@code RoomAccessGrantService}/{@code RetroAccessGrantService}
 * equivalent) — authorization is the caller's real platform identity (resolved from the same
 * bearer token the REST endpoints require) plus the same team-membership check
 * {@code WheelService}/{@code WheelDrawService} already enforce for {@code GET}/{@code POST
 * /spin}.
 *
 * <p>Only the {@code SUBSCRIBE} command is handled — unlike poker/retro, no client-to-server
 * application destination exists for wheels (the draw is entirely computed via {@code POST
 * /wheels/{wheelId}/spin}, a REST call, never a STOMP {@code SEND}), so there is nothing to rate-
 * limit or otherwise authorize on the {@code SEND} path (Gate 1 decision, documented in
 * {@code us-diffusion-ws.md}).
 *
 * <p>{@link SimpMessagingTemplate} is injected lazily to break the circular dependency that
 * would arise because this interceptor is registered on the client inbound channel during
 * Spring's message broker configuration phase, before the messaging template is fully
 * initialised (same rationale as {@code PokerChannelInterceptor}/{@code RetroChannelInterceptor}).
 */
@Component
public class WheelChannelInterceptor implements ChannelInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(WheelChannelInterceptor.class);

    /**
     * Native STOMP header carrying the caller's real platform bearer token — same header name
     * and {@code Bearer } prefix convention as the {@code Authorization} HTTP header consumed by
     * {@code RequestPrincipalResolver} for REST endpoints. Deliberately not named
     * {@code access-token} (the poker/retro convention): that header carries an opaque,
     * session-scoped grant, a different concept from the real bearer token presented here.
     */
    static final String AUTHORIZATION_HEADER = "Authorization";

    /** Case-insensitive prefix expected before the raw token value, mirroring the REST header. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Generic denial reason — never distinguishes the exact cause (see class JavaDoc). */
    private static final String ACCESS_DENIED_MESSAGE = "Access denied to wheel ";

    private final AuthenticatedPrincipalResolver principalResolver;
    private final WheelService wheelService;

    /**
     * Messaging template used to deliver error notifications to denied sessions. Lazily injected
     * to avoid a circular dependency during broker configuration (see class JavaDoc).
     */
    @Lazy
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Creates the interceptor with the required dependencies.
     *
     * @param principalResolver validates a raw bearer token, resolving the caller's real
     *                          {@code userId}/{@code tenantId} — the same bean {@code
     *                          RequestPrincipalResolver} uses for REST endpoints
     * @param wheelService      resolves whether the caller may access a given wheel, reusing the
     *                          exact same existence/tenant/team-membership check as the REST
     *                          endpoints
     */
    public WheelChannelInterceptor(
            final AuthenticatedPrincipalResolver principalResolver, final WheelService wheelService) {
        this.principalResolver = principalResolver;
        this.wheelService = wheelService;
    }

    /**
     * Intercepts inbound STOMP frames, enforcing wheel subscription authorization.
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
        if (accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return message;
        }
        return handleSubscribe(message, accessor);
    }

    /**
     * Authorises a SUBSCRIBE frame targeting a wheel topic.
     *
     * <p>Destinations that do not start with {@link WheelDestinations#TOPIC_WHEEL_PREFIX} are
     * passed through unchanged — this interceptor never acts on other domains' traffic (poker,
     * retro) or STOMP infrastructure frames.
     *
     * @param message  the SUBSCRIBE frame
     * @param accessor the STOMP header accessor
     * @return the original message if allowed, {@code null} if denied
     */
    private Message<?> handleSubscribe(final Message<?> message, final StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(WheelDestinations.TOPIC_WHEEL_PREFIX)) {
            return message;
        }
        String wheelIdStr = WheelDestinations.extractWheelId(destination);
        if (wheelIdStr == null) {
            LOG.warn("SUBSCRIBE denied: destination has no wheelId segment: {}", destination);
            return null;
        }
        UUID wheelId = parseUuid(wheelIdStr);
        if (wheelId == null) {
            LOG.warn("SUBSCRIBE denied: unparseable wheelId in destination={}", destination);
            return null;
        }

        String rawToken = extractBearerToken(accessor.getFirstNativeHeader(AUTHORIZATION_HEADER));
        Optional<AuthenticatedPrincipal> principal =
                rawToken == null ? Optional.empty() : principalResolver.resolve(rawToken);
        if (principal.isEmpty()) {
            LOG.warn("SUBSCRIBE denied: no valid bearer token for wheel={}", wheelId);
            sendError(accessor.getUser(), wheelId);
            return null;
        }

        boolean accessible =
                wheelService.isAccessibleTo(wheelId, principal.get().userId(), principal.get().tenantId());
        if (!accessible) {
            LOG.warn("SUBSCRIBE denied: caller not authorized for wheel={}", wheelId);
            sendError(accessor.getUser(), wheelId);
            return null;
        }
        return message;
    }

    /**
     * Extracts the raw token from an {@code Authorization} native header value, requiring a
     * case-insensitive {@code Bearer } prefix — mirrors {@code RequestPrincipalResolver}'s
     * identical REST-side parsing (small, deliberate duplication across the STOMP/HTTP boundary,
     * same convention already established by {@code PokerChannelInterceptor}/{@code
     * RetroChannelInterceptor} each defining their own header constants rather than a premature
     * cross-domain abstraction).
     *
     * @param authorizationHeader the raw native header value, may be {@code null}
     * @return the raw token, or {@code null} if the header is absent, malformed, or the prefix
     *     does not match
     */
    private static String extractBearerToken(final String authorizationHeader) {
        if (authorizationHeader == null
                || authorizationHeader.length() <= BEARER_PREFIX.length()
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Sends a generic denial notification to the session's {@code /user/queue/errors}
     * destination — never distinguishes missing/invalid token from a valid-but-unauthorized
     * caller (anti-enumeration, same convention as the REST 404s of US14.1.1/US14.2.1).
     *
     * @param user    the session principal (for user-scoped routing), or {@code null}
     * @param wheelId the wheel the caller attempted to subscribe to
     */
    private void sendError(final Principal user, final UUID wheelId) {
        if (user == null || messagingTemplate == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(
                    user.getName(), "/queue/errors", new WsErrorPayload(ACCESS_DENIED_MESSAGE + wheelId));
        } catch (Exception e) {
            LOG.debug("Could not deliver error notification for wheel={}: {}", wheelId, e.getMessage());
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
