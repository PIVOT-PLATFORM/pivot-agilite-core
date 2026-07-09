package fr.pivot.agilite.retro.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence access for {@link RetroSession} (US20.1.1).
 */
public interface RetroSessionRepository extends JpaRepository<RetroSession, UUID> {

    /**
     * Finds a session by id, scoped to a tenant — used by the authenticated detail endpoint to
     * enforce tenant isolation (cross-tenant access must 404, never 403, to avoid confirming
     * existence).
     *
     * @param id       the session UUID
     * @param tenantId the caller's tenant id
     * @return the matching session, or empty if not found or owned by a different tenant
     */
    Optional<RetroSession> findByIdAndTenantId(UUID id, Long tenantId);

    /**
     * Finds a session by its globally unique join code — used by the public,
     * unauthenticated join-resolution endpoint.
     *
     * @param joinCode the 6-character alphanumeric join code
     * @return the matching session, or empty if the code is unknown
     */
    Optional<RetroSession> findByJoinCode(String joinCode);

    /**
     * Checks whether a join code is already in use, for collision detection during
     * generation.
     *
     * @param joinCode the candidate join code
     * @return {@code true} if a session already uses this code
     */
    boolean existsByJoinCode(String joinCode);
}
