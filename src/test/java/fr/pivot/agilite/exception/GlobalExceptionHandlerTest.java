package fr.pivot.agilite.exception;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GlobalExceptionHandler}, directly invoking each handler method to
 * confirm the mapped HTTP status and, where applicable, the machine-readable {@code code}
 * property (US20.1.1). No Spring context is loaded.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleRetroTeamNotFound_returns404() {
        ProblemDetail problem = handler.handleRetroTeamNotFound(new RetroTeamNotFoundException(1L));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void handleRetroTeamAccessDenied_returns403() {
        ProblemDetail problem =
                handler.handleRetroTeamAccessDenied(new RetroTeamAccessDeniedException(1L));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void handleRetroSessionNotFound_returns404() {
        ProblemDetail problem = handler.handleRetroSessionNotFound(
                new RetroSessionNotFoundException(java.util.UUID.randomUUID()));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void handleRetroJoinCodeNotFound_returns404() {
        ProblemDetail problem =
                handler.handleRetroJoinCodeNotFound(new RetroJoinCodeNotFoundException());
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void handleRetroSessionExpired_returns410() {
        ProblemDetail problem =
                handler.handleRetroSessionExpired(new RetroSessionExpiredException("expired"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.GONE.value());
    }

    @Test
    void handleInvalidRetroFormat_returns400WithCode() {
        ProblemDetail problem =
                handler.handleInvalidRetroFormat(new InvalidRetroFormatException("BOGUS"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_FORMAT");
    }

    @Test
    void handleValidation_returns400WithFirstFieldErrorCode() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        org.springframework.validation.BindingResult bindingResult =
                mock(org.springframework.validation.BindingResult.class);
        org.springframework.validation.FieldError fieldError =
                new org.springframework.validation.FieldError("obj", "title", "INVALID_TITLE");
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(java.util.List.of(fieldError));

        ProblemDetail problem = handler.handleValidation(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_TITLE");
    }

    @Test
    void handleValidation_whenNoFieldErrors_usesGenericCode() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        org.springframework.validation.BindingResult bindingResult =
                mock(org.springframework.validation.BindingResult.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(java.util.List.of());

        ProblemDetail problem = handler.handleValidation(ex);

        assertThat(problem.getProperties()).containsEntry("code", "VALIDATION_ERROR");
    }

    @Test
    void handleConstraintViolation_returns400() {
        ConstraintViolationException ex = new ConstraintViolationException(Set.of());
        ProblemDetail problem = handler.handleConstraintViolation(ex);
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void handleIllegalArgument_returns400() {
        ProblemDetail problem = handler.handleIllegalArgument(new IllegalArgumentException("bad"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).isEqualTo("bad");
    }
}
