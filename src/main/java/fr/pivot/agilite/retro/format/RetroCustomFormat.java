package fr.pivot.agilite.retro.format;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA entity representing a tenant-owned custom retrospective format (US20.2.1).
 *
 * <p>The 4 predefined system formats ({@link fr.pivot.agilite.retro.session.RetroFormat},
 * excluding {@code CUSTOM}) are never rows here — they are static in-code data ({@link
 * RetroFormatCatalog}), a structural guarantee that no request of any kind can ever create,
 * modify, or delete one. Only tenant-defined {@code CUSTOM} formats are persisted, each owned by
 * exactly one tenant and never visible to another (see {@link RetroCustomFormatRepository}).
 */
@Entity
@Table(name = "retro_formats", schema = "agilite")
public class RetroCustomFormat {

    /** Primary key, also the {@code customFormatId} referenced by {@code retro_sessions}. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Owning tenant; used for multi-tenant isolation — never trusted from client input. */
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Format-level human-readable label, 1-60 characters. */
    @Column(name = "label", nullable = false, length = 60)
    private String label;

    /** User who created this format ({@code public.users.id}). */
    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    /** Timestamp when the format was first persisted. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * The format's columns, in display order. {@code @OrderColumn} persists the list index
     * directly (the {@code position} column), so retrieval order is guaranteed without relying
     * on insertion order or timestamps.
     */
    @ElementCollection
    @CollectionTable(
            name = "retro_format_columns",
            schema = "agilite",
            joinColumns = @JoinColumn(name = "format_id"),
            uniqueConstraints = @UniqueConstraint(
                    name = "uq_retro_format_columns_key", columnNames = {"format_id", "column_key"}))
    @OrderColumn(name = "position")
    private List<RetroFormatColumn> columns = new ArrayList<>();

    /** No-arg constructor required by JPA. */
    protected RetroCustomFormat() {
    }

    /**
     * Creates a new custom format with all its columns.
     *
     * @param tenantId        owning tenant's {@code public.tenants.id}
     * @param label           format-level label, 1-60 characters
     * @param createdByUserId creating user's {@code public.users.id}
     * @param columns         the format's columns, in display order (2 to 8 entries)
     */
    public RetroCustomFormat(
            final Long tenantId, final String label, final Long createdByUserId,
            final List<RetroFormatColumn> columns) {
        this.tenantId = tenantId;
        this.label = label;
        this.createdByUserId = createdByUserId;
        this.columns = new ArrayList<>(columns);
    }

    /**
     * Sets {@code createdAt} to the current instant before the first insert, when not already
     * set.
     */
    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    /**
     * Returns the format's unique identifier.
     *
     * @return the UUID
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the tenant identifier.
     *
     * @return the owning tenant's {@code public.tenants.id}
     */
    public Long getTenantId() {
        return tenantId;
    }

    /**
     * Returns the format-level label.
     *
     * @return the label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Returns the creating user's identifier.
     *
     * @return the creator's {@code public.users.id}
     */
    public Long getCreatedByUserId() {
        return createdByUserId;
    }

    /**
     * Returns the creation timestamp.
     *
     * @return the createdAt instant
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Returns the format's columns, in display order.
     *
     * @return the columns
     */
    public List<RetroFormatColumn> getColumns() {
        return columns;
    }
}
