package fr.pivot.agilite.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Read-only mirror of {@code public.users}, owned and written exclusively by {@code pivot-core}
 * — never persisted, updated, or deleted from this repo (EN08.3, US14.1.1, ADR-022).
 *
 * <p>Mapped explicitly to schema {@code public}. Unlike {@code pivot-collaboratif-core}'s
 * equivalent (which excludes every profile field), this module also maps {@code first_name},
 * {@code last_name} and {@code email} — needed to resolve a {@code team_member} wheel entry's
 * display name (US14.1.1) — in addition to {@code tenant_id}/{@code role}/{@code is_active}
 * needed to validate a bearer token and resolve the minimal {@code AuthenticatedPrincipal}.
 */
@Entity
@Table(schema = "public", name = "users")
public class PlatformUser {

    @Id
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "role", nullable = false, length = 50)
    private String role;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    /** No-argument constructor required by JPA. */
    protected PlatformUser() {
    }

    /** @return database primary key ({@code public.users.id}) */
    public Long getId() {
        return id;
    }

    /** @return the owning {@code public.tenants.id} */
    public Long getTenantId() {
        return tenantId;
    }

    /** @return the Spring Security role, e.g. {@code ROLE_USER} */
    public String getRole() {
        return role;
    }

    /** @return {@code true} unless an admin has deactivated this account */
    public boolean isActive() {
        return active;
    }

    /** @return the user's first name, or {@code null} if never set */
    public String getFirstName() {
        return firstName;
    }

    /** @return the user's last name, or {@code null} if never set */
    public String getLastName() {
        return lastName;
    }

    /** @return the user's email address */
    public String getEmail() {
        return email;
    }
}
