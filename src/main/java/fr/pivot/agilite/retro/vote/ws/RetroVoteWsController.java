package fr.pivot.agilite.retro.vote.ws;

import fr.pivot.agilite.retro.vote.RetroVoteService;
import fr.pivot.agilite.retro.vote.dto.CastVoteRequest;
import fr.pivot.agilite.retro.ws.RetroChannelInterceptor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP message handler for retrospective dot-voting (US20.1.2b).
 *
 * <p>Mapped destinations are relative to the {@code /app/agilite} application prefix (EN07.3,
 * {@code WebSocketConfig}): the full inbound destinations clients SEND to are
 * {@code /app/agilite/retro/{sessionId}/votes} (cast), {@code
 * /app/agilite/retro/{sessionId}/votes/uncast} (uncast), and
 * {@code /app/agilite/retro/{sessionId}/votes/balance} (balance query) — all matching {@link
 * fr.pivot.agilite.retro.ws.RetroSessionDestinations#APP_ROOM_PREFIX}.
 *
 * <p>By the time these handlers run, {@link RetroChannelInterceptor} has already verified the
 * caller presents a currently valid access grant for {@code sessionId} and is not being rate-
 * limited — no interceptor change was needed for these new destinations, since {@code handleSend}
 * already authorizes any destination under {@code APP_ROOM_PREFIX} generically.
 */
@Controller
public class RetroVoteWsController {

    private final RetroVoteService voteService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param voteService the dot-voting business logic service
     */
    public RetroVoteWsController(final RetroVoteService voteService) {
        this.voteService = voteService;
    }

    /**
     * Handles a vote-cast SEND frame.
     *
     * @param sessionId   the target session, from the destination's path variable
     * @param request     the target card
     * @param accessToken the caller's access token, read directly from the native STOMP header
     *                    named {@link RetroChannelInterceptor#ACCESS_TOKEN_HEADER}
     * @param principal   the caller's connection principal, used to address notifications
     */
    @MessageMapping("/retro/{sessionId}/votes")
    public void castVote(
            @DestinationVariable final UUID sessionId,
            @Payload final CastVoteRequest request,
            @Header(RetroChannelInterceptor.ACCESS_TOKEN_HEADER) final String accessToken,
            final Principal principal) {
        voteService.castVote(sessionId, request.cardId(), accessToken, principal);
    }

    /**
     * Handles a vote-uncast SEND frame.
     *
     * @param sessionId   the target session, from the destination's path variable
     * @param request     the target card
     * @param accessToken the caller's access token, read directly from the native STOMP header
     *                    named {@link RetroChannelInterceptor#ACCESS_TOKEN_HEADER}
     * @param principal   the caller's connection principal, used to address notifications
     */
    @MessageMapping("/retro/{sessionId}/votes/uncast")
    public void uncastVote(
            @DestinationVariable final UUID sessionId,
            @Payload final CastVoteRequest request,
            @Header(RetroChannelInterceptor.ACCESS_TOKEN_HEADER) final String accessToken,
            final Principal principal) {
        voteService.uncastVote(sessionId, request.cardId(), accessToken, principal);
    }

    /**
     * Handles a balance-query SEND frame — empty body, the caller simply asks to be told their
     * current remaining balance (typically once, on entering the {@code VOTE} phase, and again on
     * reconnect).
     *
     * @param sessionId   the target session, from the destination's path variable
     * @param accessToken the caller's access token, read directly from the native STOMP header
     *                    named {@link RetroChannelInterceptor#ACCESS_TOKEN_HEADER}
     * @param principal   the caller's connection principal, used to address the notification
     */
    @MessageMapping("/retro/{sessionId}/votes/balance")
    public void queryBalance(
            @DestinationVariable final UUID sessionId,
            @Header(RetroChannelInterceptor.ACCESS_TOKEN_HEADER) final String accessToken,
            final Principal principal) {
        voteService.queryBalance(sessionId, accessToken, principal);
    }
}
