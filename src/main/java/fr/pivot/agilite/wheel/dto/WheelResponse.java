package fr.pivot.agilite.wheel.dto;

import fr.pivot.agilite.wheel.Wheel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response payload representing a wheel visible to the caller (US14.1.1).
 *
 * @param id               unique identifier of the wheel
 * @param name             human-readable wheel name
 * @param teamId           {@code public.teams.id} this wheel belongs to
 * @param tenantId          {@code public.tenants.id} of the tenant that owns this wheel
 * @param entries          the wheel's entries
 * @param lastDrawnEntryId the last-drawn entry's id, or {@code null} — always {@code null} until
 *                         US14.2.1's weighted draw lands
 * @param createdAt        timestamp when the wheel was created
 * @param updatedAt        timestamp of the last wheel update
 */
public record WheelResponse(
        UUID id,
        String name,
        Long teamId,
        Long tenantId,
        List<WheelEntryResponse> entries,
        UUID lastDrawnEntryId,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Factory method that creates a {@link WheelResponse} from a {@link Wheel} entity.
     *
     * @param wheel the wheel entity
     * @return a populated response record
     */
    public static WheelResponse from(final Wheel wheel) {
        return new WheelResponse(
                wheel.getId(),
                wheel.getName(),
                wheel.getTeamId(),
                wheel.getTenantId(),
                wheel.getEntries().stream().map(WheelEntryResponse::from).toList(),
                wheel.getLastDrawnEntryId(),
                wheel.getCreatedAt(),
                wheel.getUpdatedAt());
    }
}
