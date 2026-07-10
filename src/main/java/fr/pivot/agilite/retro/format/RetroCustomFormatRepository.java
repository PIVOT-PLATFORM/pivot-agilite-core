package fr.pivot.agilite.retro.format;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence access for {@link RetroCustomFormat} (US20.2.1).
 */
public interface RetroCustomFormatRepository extends JpaRepository<RetroCustomFormat, UUID> {

    /**
     * Finds a custom format by id, scoped to a tenant — used by {@code POST /retro/sessions}'s
     * {@code customFormatId} resolution: cross-tenant access must 404, never 403, to avoid
     * confirming existence of another tenant's format.
     *
     * @param id       the format UUID
     * @param tenantId the caller's tenant id
     * @return the matching format, or empty if not found or owned by a different tenant
     */
    Optional<RetroCustomFormat> findByIdAndTenantId(UUID id, Long tenantId);

    /**
     * Returns every custom format owned by a tenant, oldest first — appended after the 4 system
     * formats by {@code GET /retro/formats}.
     *
     * @param tenantId the owning tenant's id
     * @return the tenant's custom formats, ordered by creation time
     */
    List<RetroCustomFormat> findByTenantIdOrderByCreatedAtAsc(Long tenantId);
}
