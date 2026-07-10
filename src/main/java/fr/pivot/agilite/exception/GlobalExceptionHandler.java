package fr.pivot.agilite.exception;

import fr.pivot.agilite.poker.exception.InviteCodeNotFoundException;
import fr.pivot.agilite.poker.exception.RoomNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Global exception handler that maps domain exceptions to RFC 7807 Problem Detail responses.
 *
 * <p>Handles retro-session domain exceptions ({@link RetroTeamNotFoundException}, {@link
 * RetroTeamAccessDeniedException}, {@link RetroSessionNotFoundException}, {@link
 * RetroJoinCodeNotFoundException}, {@link RetroSessionExpiredException}, {@link
 * InvalidRetroFormatException}), retro-session phase/facilitator exceptions (US20.1.2a, {@link
 * RetroFacilitatorOnlyException}, {@link RetroInvalidPhaseTransitionException}), the US20.2.1
 * custom-format cross-field validation exceptions on session creation ({@link
 * RetroCustomFormatIdRequiredException}, {@link RetroCustomFormatNotFoundException}, {@link
 * RetroCustomFormatIdNotAllowedException}), planning poker room lookup failures (US09.1.1, {@link
 * RoomNotFoundException}) and join-by-code failures (US09.1.2, {@link
 * InviteCodeNotFoundException}), wheel/team domain exceptions (US14.1.1, {@link
 * WheelNotFoundException}, {@link TeamNotFoundException}, {@link WheelValidationException}), as
 * well as Spring MVC/Bean Validation failures ({@link MethodArgumentNotValidException}, {@link
 * ConstraintViolationException}).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Returns HTTP 404 when a planning poker room is not found, or belongs to another tenant
     * (US09.1.1) — both cases are deliberately indistinguishable to avoid confirming
     * cross-tenant existence.
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(RoomNotFoundException.class)
    public ProblemDetail handleRoomNotFound(final RoomNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Room not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 404 when a planning poker invite code does not resolve to a room currently
     * joinable by the caller's tenant (US09.1.2) — collapsing an unknown code, a code belonging
     * to another tenant, a code for a deactivated room, and a code for an expired room into the
     * exact same response (ADR-026 §2), unlike {@link RetroSessionExpiredException}'s separate
     * 410 for the retro-session join flow.
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(InviteCodeNotFoundException.class)
    public ProblemDetail handleInviteCodeNotFound(final InviteCodeNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Invite code not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 404 when a team referenced in a retro session request does not exist, or
     * belongs to a different tenant than the caller.
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(RetroTeamNotFoundException.class)
    public ProblemDetail handleRetroTeamNotFound(final RetroTeamNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Team not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 403 when the caller is not a member of a team that genuinely exists in their
     * own tenant.
     *
     * @param ex the thrown exception
     * @return a 403 problem detail
     */
    @ExceptionHandler(RetroTeamAccessDeniedException.class)
    public ProblemDetail handleRetroTeamAccessDenied(final RetroTeamAccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Access denied");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 404 when a retro session is not found, or belongs to another tenant.
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(RetroSessionNotFoundException.class)
    public ProblemDetail handleRetroSessionNotFound(final RetroSessionNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Retro session not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 404 when a join code does not resolve to any existing retro session.
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(RetroJoinCodeNotFoundException.class)
    public ProblemDetail handleRetroJoinCodeNotFound(final RetroJoinCodeNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Join code not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 410 Gone when a retro session has expired or is already closed, at
     * join-resolution time.
     *
     * @param ex the thrown exception
     * @return a 410 problem detail
     */
    @ExceptionHandler(RetroSessionExpiredException.class)
    public ProblemDetail handleRetroSessionExpired(final RetroSessionExpiredException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.GONE);
        problem.setTitle("Retro session expired");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 403 when an authenticated, same-tenant caller attempts a facilitator-only
     * retro session action but is not that session's facilitator (US20.1.2a).
     *
     * @param ex the thrown exception
     * @return a 403 problem detail
     */
    @ExceptionHandler(RetroFacilitatorOnlyException.class)
    public ProblemDetail handleRetroFacilitatorOnly(final RetroFacilitatorOnlyException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Facilitator only");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 409 when a retro session phase-dependent action is attempted while the
     * session is not in the required phase (US20.1.2a).
     *
     * @param ex the thrown exception
     * @return a 409 problem detail
     */
    @ExceptionHandler(RetroInvalidPhaseTransitionException.class)
    public ProblemDetail handleRetroInvalidPhaseTransition(final RetroInvalidPhaseTransitionException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Invalid phase transition");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 400 with a machine-readable {@code code} property when the {@code format}
     * field does not match any known {@link fr.pivot.agilite.retro.session.RetroFormat} value.
     *
     * @param ex the thrown exception
     * @return a 400 problem detail with {@code { "code": "INVALID_FORMAT" } }
     */
    @ExceptionHandler(InvalidRetroFormatException.class)
    public ProblemDetail handleInvalidRetroFormat(final InvalidRetroFormatException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid retro format");
        problem.setProperties(Map.of("code", "INVALID_FORMAT"));
        return problem;
    }

    /**
     * Returns HTTP 400 with a machine-readable {@code code} property when {@code format ==
     * "CUSTOM"} but no {@code customFormatId} was supplied on retro session creation (US20.2.1).
     *
     * @param ex the thrown exception
     * @return a 400 problem detail with {@code { "code": "CUSTOM_FORMAT_ID_REQUIRED" } }
     */
    @ExceptionHandler(RetroCustomFormatIdRequiredException.class)
    public ProblemDetail handleRetroCustomFormatIdRequired(final RetroCustomFormatIdRequiredException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Custom format id required");
        problem.setProperties(Map.of("code", "CUSTOM_FORMAT_ID_REQUIRED"));
        return problem;
    }

    /**
     * Returns HTTP 404 with a machine-readable {@code code} property when a {@code
     * customFormatId} supplied on retro session creation does not resolve to a custom format
     * owned by the caller's tenant (US20.2.1) — never 403, to avoid confirming cross-tenant
     * existence.
     *
     * @param ex the thrown exception
     * @return a 404 problem detail with {@code { "code": "CUSTOM_FORMAT_NOT_FOUND" } }
     */
    @ExceptionHandler(RetroCustomFormatNotFoundException.class)
    public ProblemDetail handleRetroCustomFormatNotFound(final RetroCustomFormatNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Custom format not found");
        problem.setProperties(Map.of("code", "CUSTOM_FORMAT_NOT_FOUND"));
        return problem;
    }

    /**
     * Returns HTTP 400 with a machine-readable {@code code} property when a non-{@code CUSTOM}
     * retro session creation request supplies a {@code customFormatId} anyway (US20.2.1) —
     * rejected explicitly rather than silently ignored.
     *
     * @param ex the thrown exception
     * @return a 400 problem detail with {@code { "code": "CUSTOM_FORMAT_ID_NOT_ALLOWED" } }
     */
    @ExceptionHandler(RetroCustomFormatIdNotAllowedException.class)
    public ProblemDetail handleRetroCustomFormatIdNotAllowed(final RetroCustomFormatIdNotAllowedException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Custom format id not allowed");
        problem.setProperties(Map.of("code", "CUSTOM_FORMAT_ID_NOT_ALLOWED"));
        return problem;
    }

    /**
     * Returns HTTP 404 when a wheel is not found, belongs to another tenant, or the caller is
     * not a member of its team (US14.1.1).
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(WheelNotFoundException.class)
    public ProblemDetail handleWheelNotFound(final WheelNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Wheel not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 404 when a team is not found, belongs to another tenant, or the caller is
     * not one of its members (US14.1.1).
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(TeamNotFoundException.class)
    public ProblemDetail handleTeamNotFound(final TeamNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Team not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 400 with a machine-readable {@code code} property for wheel entry business
     * rule violations (duplicate entry, invalid team-member reference, malformed entry)
     * (US14.1.1).
     *
     * @param ex the thrown exception
     * @return a 400 problem detail with a {@code code} property
     */
    @ExceptionHandler(WheelValidationException.class)
    public ProblemDetail handleWheelValidation(final WheelValidationException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail(ex.getMessage());
        problem.setProperties(Map.of("code", ex.getCode()));
        return problem;
    }

    /**
     * Returns HTTP 400 with a machine-readable {@code code} property for Bean Validation
     * failures on request bodies.
     *
     * <p>The {@code code} value is extracted from the first field error's default message,
     * which is set to values such as {@code "INVALID_TITLE"} by the validation constraints on
     * request DTOs (e.g. {@code CreateRetroSessionRequest}, wheel request DTOs).
     *
     * @param ex the validation exception
     * @return a 400 problem detail with a {@code code} property
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(final MethodArgumentNotValidException ex) {
        String firstMessage = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(fe -> fe.getDefaultMessage())
                .orElse("VALIDATION_ERROR");
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setProperties(Map.of("code", firstMessage));
        return problem;
    }

    /**
     * Returns HTTP 400 for parameter constraint violations (e.g. path variable constraints,
     * missing/invalid {@code teamId} query parameter).
     *
     * @param ex the constraint violation exception
     * @return a 400 problem detail
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(final ConstraintViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 400 for invalid argument values (defensive service-layer guard).
     *
     * @param ex the illegal argument exception
     * @return a 400 problem detail
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(final IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Bad request");
        problem.setDetail(ex.getMessage());
        return problem;
    }
}
