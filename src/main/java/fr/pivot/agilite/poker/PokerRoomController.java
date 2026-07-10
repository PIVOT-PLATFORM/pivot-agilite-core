package fr.pivot.agilite.poker;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.poker.dto.AnonymousJoinRequest;
import fr.pivot.agilite.poker.dto.AnonymousJoinResponse;
import fr.pivot.agilite.poker.dto.CreateRoomRequest;
import fr.pivot.agilite.poker.dto.GuestHeartbeatRequest;
import fr.pivot.agilite.poker.dto.GuestHeartbeatResponse;
import fr.pivot.agilite.poker.dto.JoinRoomRequest;
import fr.pivot.agilite.poker.dto.JoinRoomResponse;
import fr.pivot.agilite.poker.dto.RoomResponse;
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
 * REST controller exposing planning poker room operations under {@code /poker/rooms} (US09.1.1),
 * including join-by-code ({@code POST /join}, US09.1.2) and anonymous guest participation
 * ({@code POST /join-anonymous}, {@code POST /{roomId}/guest-sessions/heartbeat}, US09.3.1).
 *
 * <p>Most endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link RequestPrincipal} by {@link fr.pivot.agilite.context.RequestPrincipalResolver}. Missing,
 * malformed, or rejected tokens result in HTTP 401. {@link #joinAnonymous} and {@link
 * #guestHeartbeat} are the deliberate exception (US09.3.1, ADR-026 §2): neither declares a {@link
 * RequestPrincipal} parameter, so no attempt is ever made to resolve an {@code Authorization}
 * header for these two — a header supplied anyway is simply ignored.
 *
 * <p>The full path (including the application context) is {@code /api/agilite/poker/rooms}.
 */
@RestController
@RequestMapping("/poker/rooms")
public class PokerRoomController {

    private final PokerRoomService service;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param service the room business logic service
     */
    public PokerRoomController(final PokerRoomService service) {
        this.service = service;
    }

    /**
     * Creates a new planning poker room. The caller is automatically assigned as facilitator.
     *
     * @param request   the room creation request — non-blank name (max 120 chars), optional
     *                  expiration in hours (1-168)
     * @param principal the resolved caller identity (user + tenant)
     * @return the created room with HTTP 201 Created
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomResponse create(
            @RequestBody @Valid final CreateRoomRequest request,
            final RequestPrincipal principal) {
        return service.create(
                request.name(), principal.userId(), principal.tenantId(), request.expirationHours());
    }

    /**
     * Returns a single room by its identifier, scoped to the caller's tenant.
     *
     * @param roomId    the room id from the path
     * @param principal the resolved caller identity
     * @return the room, or HTTP 404 if it does not exist or belongs to another tenant
     */
    @GetMapping("/{roomId}")
    public RoomResponse findById(
            @PathVariable final UUID roomId,
            final RequestPrincipal principal) {
        return service.findById(roomId, principal.tenantId());
    }

    /**
     * Joins an existing planning poker room via its invite code (US09.1.2).
     *
     * <p>An unknown code, a code belonging to another tenant, a code for a deactivated room, and
     * a code for an expired room all result in the same HTTP 404 — never distinguished (ADR-026
     * §2).
     *
     * @param request   the join request — the 6-character invite code
     * @param principal the resolved caller identity (user + tenant)
     * @return the joined room's details and a freshly minted WebSocket access token, HTTP 200
     */
    @PostMapping("/join")
    public JoinRoomResponse join(
            @RequestBody @Valid final JoinRoomRequest request,
            final RequestPrincipal principal) {
        return service.join(request.code(), principal.tenantId());
    }

    /**
     * Joins an existing planning poker room anonymously, via its invite code, with no account and
     * no bearer token at all (US09.3.1, ADR-026 §2).
     *
     * <p>Deliberately declares no {@link RequestPrincipal} parameter — no {@code Authorization}
     * header is ever read or validated for this endpoint. An unknown code, a code belonging to a
     * deactivated room, and a code for an expired room all result in the same HTTP 404 as {@link
     * #join} — never distinguished.
     *
     * @param request the anonymous join request — invite code and optional pseudonym
     * @return the joined room's details, a room-scoped {@code accessToken}, a temporary {@code
     *         sessionId} (never persisted), and the resolved pseudonym, HTTP 200
     */
    @PostMapping("/join-anonymous")
    public AnonymousJoinResponse joinAnonymous(
            @RequestBody @Valid final AnonymousJoinRequest request) {
        return service.joinAnonymous(request.code(), request.pseudonym());
    }

    /**
     * Keeps an anonymous guest session alive past its 2h-inactivity cap (US09.3.1).
     *
     * <p>Deliberately declares no {@link RequestPrincipal} parameter, same as {@link
     * #joinAnonymous} — the {@code accessToken} in the request body is the sole credential
     * checked, against the room-scoped grant store, never against any user identity.
     *
     * @param roomId  the room id from the path
     * @param request the heartbeat request carrying the guest's {@code accessToken}
     * @return the refreshed guest session expiry, HTTP 200
     */
    @PostMapping("/{roomId}/guest-sessions/heartbeat")
    public GuestHeartbeatResponse guestHeartbeat(
            @PathVariable final UUID roomId,
            @RequestBody @Valid final GuestHeartbeatRequest request) {
        return service.refreshGuestSession(roomId, request.accessToken());
    }
}
