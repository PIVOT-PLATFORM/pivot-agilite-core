package fr.pivot.agilite.retro.card.ws;

import fr.pivot.agilite.retro.card.RetroCardService;
import fr.pivot.agilite.retro.card.dto.SubmitCardRequest;
import fr.pivot.agilite.retro.ws.RetroChannelInterceptor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP message handler for retrospective card submission (US20.1.2a).
 *
 * <p>Mapped destination is relative to the {@code /app/agilite} application prefix (EN07.3,
 * {@code WebSocketConfig}): the full inbound destination clients SEND to is
 * {@code /app/agilite/retro/{sessionId}/cards}, matching {@link
 * fr.pivot.agilite.retro.ws.RetroSessionDestinations#APP_ROOM_PREFIX}.
 *
 * <p>By the time this handler runs, {@link RetroChannelInterceptor} has already verified the
 * caller presents a currently valid access grant for {@code sessionId} and is not being rate-
 * limited — this handler does not need to repeat authorization, only re-resolve the grant's
 * identity (via {@link RetroCardService}) to know who is submitting.
 */
@Controller
public class RetroCardWsController {

    private final RetroCardService cardService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param cardService the card submission business logic service
     */
    public RetroCardWsController(final RetroCardService cardService) {
        this.cardService = cardService;
    }

    /**
     * Handles a card submission SEND frame.
     *
     * @param sessionId   the target session, from the destination's path variable
     * @param request     the submitted content/column/anonymous flag
     * @param accessToken the caller's access token, read directly from the native STOMP header
     *                    named {@link RetroChannelInterceptor#ACCESS_TOKEN_HEADER}
     * @param principal   the caller's connection principal, used to address error notifications
     */
    @MessageMapping("/retro/{sessionId}/cards")
    public void submitCard(
            @DestinationVariable final UUID sessionId,
            @Payload final SubmitCardRequest request,
            @Header(RetroChannelInterceptor.ACCESS_TOKEN_HEADER) final String accessToken,
            final Principal principal) {
        cardService.submit(sessionId, request, accessToken, principal);
    }
}
