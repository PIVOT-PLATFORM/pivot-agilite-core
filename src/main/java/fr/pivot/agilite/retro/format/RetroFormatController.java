package fr.pivot.agilite.retro.format;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.retro.format.dto.CreateRetroFormatRequest;
import fr.pivot.agilite.retro.format.dto.RetroFormatListResponse;
import fr.pivot.agilite.retro.format.dto.RetroFormatResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the retrospective format catalogue under {@code /retro/formats}
 * (US20.2.1).
 *
 * <p>Full path (including the application context) is {@code /api/agilite/retro/formats}. Both
 * operations require a valid {@code Authorization: Bearer <token>} header — unlike {@code GET
 * /retro/sessions/join/{joinCode}}, nothing here is ever public. There is deliberately no route
 * of any kind ({@code PUT}/{@code PATCH}/{@code DELETE}) on {@code /retro/formats/{key}} — the 4
 * system formats are immutable in-code data ({@link RetroFormatCatalog}), and custom-format
 * {@code key}s are always server-generated, never client-chosen, so there is no route to target
 * even in principle.
 */
@RestController
@RequestMapping("/retro/formats")
@Validated
public class RetroFormatController {

    private final RetroFormatService formatService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param formatService the retro format business logic service
     */
    public RetroFormatController(final RetroFormatService formatService) {
        this.formatService = formatService;
    }

    /**
     * Lists the format catalogue: the 4 predefined system formats, always present and in a fixed
     * order, followed by the calling tenant's own custom formats.
     *
     * @param principal the resolved caller identity (user + tenant)
     * @return the ordered catalogue
     */
    @GetMapping
    public RetroFormatListResponse list(final RequestPrincipal principal) {
        return formatService.listFormats(principal.tenantId());
    }

    /**
     * Creates a new tenant-owned custom retrospective format.
     *
     * @param request   the creation request — format-level label and 2 to 8 columns
     * @param principal the resolved caller identity (user + tenant)
     * @return the created format with HTTP 201 Created
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RetroFormatResponse create(
            @RequestBody @Valid final CreateRetroFormatRequest request,
            final RequestPrincipal principal) {
        return formatService.createFormat(request, principal.userId(), principal.tenantId());
    }
}
