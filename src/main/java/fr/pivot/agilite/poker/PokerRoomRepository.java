package fr.pivot.agilite.poker;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for {@link PokerRoom} (US09.1.1), schema {@code agilite}.
 */
public interface PokerRoomRepository extends JpaRepository<PokerRoom, Long> {

    /**
     * Finds a room by id, scoped to the given tenant — the transversal tenant-isolation pattern
     * used across this repo's endpoints (see {@code CLAUDE.md}): a room belonging to another
     * tenant is treated identically to a non-existent room.
     *
     * @param id       the room's primary key
     * @param tenantId the caller's tenant id, resolved server-side from the bearer token
     * @return the matching room, or empty if it does not exist or belongs to another tenant
     */
    Optional<PokerRoom> findByIdAndTenantId(Long id, Long tenantId);

    /**
     * Checks whether an invite code is already in use, for the uniqueness retry loop in
     * {@link PokerRoomService}.
     *
     * @param inviteCode the candidate invite code
     * @return {@code true} if a room already uses this invite code
     */
    boolean existsByInviteCode(String inviteCode);
}
