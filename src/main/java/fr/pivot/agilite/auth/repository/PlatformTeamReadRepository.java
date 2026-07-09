package fr.pivot.agilite.auth.repository;

import fr.pivot.agilite.auth.entity.PlatformTeam;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Read-only access to {@code public.teams} (US14.1.1).
 *
 * <p>Extends Spring Data's bare {@link Repository} marker — not {@code JpaRepository} — so no
 * {@code save}/{@code delete} method is ever exposed: this repo never writes to {@code teams}.
 */
public interface PlatformTeamReadRepository extends Repository<PlatformTeam, Long> {

    /**
     * Finds a team by primary key, scoped to the expected tenant.
     *
     * <p>Returns {@link Optional#empty()} if the team does not exist or belongs to a different
     * tenant, preventing cross-tenant information disclosure.
     *
     * @param id       the {@code public.teams.id} to look up
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @return the matching team, or empty if not found or owned by another tenant
     */
    Optional<PlatformTeam> findByIdAndTenantId(Long id, Long tenantId);
}
