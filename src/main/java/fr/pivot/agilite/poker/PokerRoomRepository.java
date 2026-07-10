package fr.pivot.agilite.poker;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link PokerRoom} (US09.1.1), schema {@code agilite}.
 */
public interface PokerRoomRepository extends JpaRepository<PokerRoom, UUID> {

    /**
     * Finds a room by id, scoped to the given tenant — the transversal tenant-isolation pattern
     * used across this repo's endpoints (see {@code CLAUDE.md}): a room belonging to another
     * tenant is treated identically to a non-existent room.
     *
     * @param id       the room's primary key
     * @param tenantId the caller's tenant id, resolved server-side from the bearer token
     * @return the matching room, or empty if it does not exist or belongs to another tenant
     */
    Optional<PokerRoom> findByIdAndTenantId(UUID id, Long tenantId);

    /**
     * Checks whether an invite code is already in use, for the uniqueness retry loop in
     * {@link PokerRoomService}.
     *
     * @param inviteCode the candidate invite code
     * @return {@code true} if a room already uses this invite code
     */
    boolean existsByInviteCode(String inviteCode);

    /**
     * Finds a room by its invite code, globally — not scoped to any tenant. Invite codes are
     * unique platform-wide by construction ({@link InviteCodeGenerator}, enforced at creation
     * time by the retry loop above and the {@code unique} DB constraint), so there is exactly one
     * candidate room to resolve before {@link PokerRoomService#join} performs its own tenant/
     * active/expiry checks.
     *
     * @param inviteCode the invite code to resolve
     * @return the matching room, or empty if no room currently uses this invite code
     */
    Optional<PokerRoom> findByInviteCode(String inviteCode);
}
