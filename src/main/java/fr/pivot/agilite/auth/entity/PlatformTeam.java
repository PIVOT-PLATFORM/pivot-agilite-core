package fr.pivot.agilite.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Read-only mirror of {@code public.teams}, owned and written exclusively by {@code pivot-core}
 * — never persisted, updated, or deleted from this repo (US14.1.1).
 *
 * <p>Mapped explicitly to schema {@code public}. This module (like every {@code pivot-xxx-core}
 * module) references {@code public.teams.id} by cross-schema foreign key (ADR-006) rather than
 * duplicating the team concept locally, and needs {@code tenant_id} to verify a wheel's
 * {@code teamId} belongs to the caller's own tenant before any further processing.
 */
@Entity
@Table(schema = "public", name = "teams")
public class PlatformTeam {

    @Id
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** No-argument constructor required by JPA. */
    protected PlatformTeam() {
    }

    /** @return database primary key ({@code public.teams.id}) */
    public Long getId() {
        return id;
    }

    /** @return the owning {@code public.tenants.id} */
    public Long getTenantId() {
        return tenantId;
    }

    /** @return the team's display name */
    public String getName() {
        return name;
    }
}
