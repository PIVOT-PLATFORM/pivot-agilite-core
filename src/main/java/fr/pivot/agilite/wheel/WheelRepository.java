package fr.pivot.agilite.wheel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Wheel} entities (US14.1.1).
 */
public interface WheelRepository extends JpaRepository<Wheel, UUID> {

    /**
     * Finds a wheel by its identifier, verifying it belongs to the given tenant.
     *
     * <p>Returns {@link Optional#empty()} if the wheel does not exist or belongs to a different
     * tenant, preventing cross-tenant information disclosure.
     *
     * @param id       the wheel UUID
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @return an {@link Optional} containing the wheel, or empty if not found
     */
    Optional<Wheel> findByIdAndTenantId(UUID id, Long tenantId);

    /**
     * Finds all wheels belonging to a team, scoped to the given tenant.
     *
     * @param teamId   the team's {@code public.teams.id}
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @return all wheels of that team
     */
    List<Wheel> findAllByTeamIdAndTenantId(Long teamId, Long tenantId);
}
