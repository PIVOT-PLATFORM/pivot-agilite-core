package fr.pivot.agilite.exception;

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
 * InvalidRetroFormatException}), planning poker room lookup failures (US09.1.1, {@link
 * RoomNotFoundException}), as well as Spring MVC/Bean Validation failures ({@link
 * MethodArgumentNotValidException}, {@link ConstraintViolationException}).
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
     * Returns HTTP 400 with a machine-readable {@code code} property for Bean Validation
     * failures on request bodies.
     *
     * <p>The {@code code} value is extracted from the first field error's default message,
     * which is set to values such as {@code "INVALID_TITLE"} by the validation constraints on
     * {@code CreateRetroSessionRequest}.
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
     * Returns HTTP 400 for parameter constraint violations (e.g. path variable constraints).
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
