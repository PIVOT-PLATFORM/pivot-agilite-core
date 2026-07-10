package fr.pivot.agilite.poker.vote.ws;

import fr.pivot.agilite.poker.vote.PokerVoteService;
import fr.pivot.agilite.poker.vote.dto.SubmitVoteRequest;
import fr.pivot.agilite.poker.ws.PokerChannelInterceptor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP message handler for planning poker vote submission (US09.2.1).
 *
 * <p>Mapped destination is relative to the {@code /app/agilite} application prefix (EN07.3,
 * {@code WebSocketConfig}): the full inbound destination clients SEND to is
 * {@code /app/agilite/poker/{roomId}/vote}, matching {@link
 * fr.pivot.agilite.poker.ws.PokerRoomDestinations#APP_ROOM_PREFIX} — already exercised as a
 * stand-in destination by {@code PokerRateLimitEnforcementIT} (EN09.1) before this handler
 * existed.
 *
 * <p>By the time this handler runs, {@link PokerChannelInterceptor} has already verified the
 * caller presents a currently valid access grant for {@code roomId} and is not being rate-limited
 * — this handler does not repeat authorization, only forwards the access token (for identity
 * hashing) to {@link PokerVoteService}.
 */
@Controller
public class PokerVoteWsController {

    private final PokerVoteService voteService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param voteService the vote submission business logic service
     */
    public PokerVoteWsController(final PokerVoteService voteService) {
        this.voteService = voteService;
    }

    /**
     * Handles a vote submission SEND frame.
     *
     * @param roomId      the target room, from the destination's path variable
     * @param request     the ticket id and chosen card value
     * @param accessToken the caller's access token, read directly from the native STOMP header
     *                    named {@link PokerChannelInterceptor#ACCESS_TOKEN_HEADER}
     * @param principal   the caller's connection principal, used to address error notifications
     */
    @MessageMapping("/poker/{roomId}/vote")
    public void submitVote(
            @DestinationVariable final UUID roomId,
            @Payload final SubmitVoteRequest request,
            @Header(PokerChannelInterceptor.ACCESS_TOKEN_HEADER) final String accessToken,
            final Principal principal) {
        voteService.submit(roomId, request, accessToken, principal);
    }
}
