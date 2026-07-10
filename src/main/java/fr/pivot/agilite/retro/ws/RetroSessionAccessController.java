package fr.pivot.agilite.retro.ws;

import fr.pivot.agilite.retro.ws.dto.RetroParticipantAccessResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing the retro session join/access-grant operation under
 * {@code /retro/sessions/{id}/participants} (US20.1.2a).
 *
 * <p>Full path (including the application context) is
 * {@code /api/agilite/retro/sessions/{id}/participants}.
 *
 * <p><strong>Deliberately does not require {@code Authorization}</strong> — unlike {@code
 * RetroSessionController}'s {@code create}/{@code findById}, this endpoint must also serve
 * account-less participants who joined via {@code joinCode} alone (US20.1.1's frictionless join
 * design pillar). The {@code Authorization} header is read manually (never via {@code
 * RequestPrincipal}, which would reject the request outright when absent) and passed through
 * best-effort to {@link RetroSessionAccessService}, which downgrades any missing/invalid/cross-
 * tenant token to an anonymous grant rather than failing the request.
 */
@RestController
@RequestMapping("/retro/sessions/{id}/participants")
public class RetroSessionAccessController {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final RetroSessionAccessService accessService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param accessService the join/access-grant business logic service
     */
    public RetroSessionAccessController(final RetroSessionAccessService accessService) {
        this.accessService = accessService;
    }

    /**
     * Joins a retro session's realtime channel, minting a fresh access grant for the caller.
     *
     * @param id      the session UUID from the path
     * @param request the current HTTP request, used to best-effort read an optional bearer token
     * @return the minted access grant, with HTTP 201 Created
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RetroParticipantAccessResponse join(
            @PathVariable final UUID id, final HttpServletRequest request) {
        return accessService.join(id, extractBearerToken(request.getHeader(AUTHORIZATION_HEADER)));
    }

    /**
     * Extracts the raw token from an {@code Authorization} header value, requiring a
     * case-insensitive {@code Bearer } prefix — best-effort, never throws.
     *
     * @param authorizationHeader the raw {@code Authorization} header value, may be {@code null}
     * @return the raw token, or {@code null} if the header is absent, malformed, or the prefix
     *     does not match
     */
    private static String extractBearerToken(final String authorizationHeader) {
        if (authorizationHeader == null
                || authorizationHeader.length() <= BEARER_PREFIX.length()
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        final String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
