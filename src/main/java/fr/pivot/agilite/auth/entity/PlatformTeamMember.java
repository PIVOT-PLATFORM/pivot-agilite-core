package fr.pivot.agilite.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Read-only mirror of {@code public.team_members}, owned and written exclusively by
 * {@code pivot-core} — never persisted, updated, or deleted from this repo (US14.1.1).
 *
 * <p>Mapped explicitly to schema {@code public}. Backs both the "import members natively"
 * differentiator of the wheel feature (a wheel entry may reference a {@code team_members.id})
 * and the team-membership check gating every {@code /wheels}/{@code /teams} endpoint.
 */
@Entity
@Table(schema = "public", name = "team_members")
public class PlatformTeamMember {

    @Id
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** No-argument constructor required by JPA. */
    protected PlatformTeamMember() {
    }

    /** @return database primary key ({@code public.team_members.id}) */
    public Long getId() {
        return id;
    }

    /** @return the owning {@code public.teams.id} */
    public Long getTeamId() {
        return teamId;
    }

    /** @return the member's {@code public.users.id} */
    public Long getUserId() {
        return userId;
    }
}
