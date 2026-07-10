package fr.pivot.agilite.poker.ticket;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.poker.ticket.dto.CreateTicketRequest;
import fr.pivot.agilite.poker.ticket.dto.TicketResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing planning poker ticket operations under {@code
 * /poker/rooms/{roomId}/tickets} (US09.2.1).
 *
 * <p>All endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link RequestPrincipal} by {@code RequestPrincipalResolver}. Missing, malformed, or rejected
 * tokens result in HTTP 401.
 *
 * <p>The full path (including the application context) is {@code
 * /api/agilite/poker/rooms/{roomId}/tickets}.
 */
@RestController
@RequestMapping("/poker/rooms/{roomId}/tickets")
public class PokerTicketController {

    private final PokerTicketService service;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param service the ticket business logic service
     */
    public PokerTicketController(final PokerTicketService service) {
        this.service = service;
    }

    /**
     * Creates a new ticket in a room, restricted to that room's facilitator.
     *
     * @param roomId    the target room
     * @param request   the ticket creation request — non-blank title (max 200 chars)
     * @param principal the resolved caller identity (user + tenant)
     * @return the created ticket with HTTP 201 Created
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse create(
            @PathVariable final UUID roomId,
            @RequestBody @Valid final CreateTicketRequest request,
            final RequestPrincipal principal) {
        return service.create(roomId, request.title(), principal.userId(), principal.tenantId());
    }

    /**
     * Returns the room's currently open ({@code VOTING}) ticket, if any.
     *
     * @param roomId    the target room
     * @param principal the resolved caller identity (user + tenant)
     * @return the open ticket, or {@code null} (HTTP 200 with an empty body) if none is open
     */
    @GetMapping("/current")
    public TicketResponse current(
            @PathVariable final UUID roomId,
            final RequestPrincipal principal) {
        return service.getCurrent(roomId, principal.tenantId()).orElse(null);
    }
}
