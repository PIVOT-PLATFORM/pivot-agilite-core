package fr.pivot.agilite.retro.format;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * A single column of a tenant-owned custom retrospective format, embedded (not a standalone
 * entity) inside {@link RetroCustomFormat#getColumns()} (US20.2.1).
 *
 * <p>{@code key} is always server-generated (an uppercase ASCII slug of the column's label,
 * disambiguated on collision within the same format by {@link RetroFormatService}) — never
 * client-chosen, so a custom format's columns can never collide with, or be mistaken for, a
 * system format's fixed column keys (part of the structural immutability guarantee for system
 * formats).
 */
@Embeddable
public class RetroFormatColumn {

    /** Server-generated slug, unique within the owning format. */
    @Column(name = "column_key", nullable = false, length = 50)
    private String key;

    /** Human-readable column label as supplied by the caller. */
    @Column(name = "label", nullable = false, length = 40)
    private String label;

    /** Hex color code — caller-supplied, or defaulted by position if omitted. */
    @Column(name = "color", length = 20)
    private String color;

    /** Optional column description, {@code null} if not supplied. */
    @Column(name = "description", length = 200)
    private String description;

    /** Optional icon identifier, {@code null} if not supplied. */
    @Column(name = "icon", length = 50)
    private String icon;

    /** No-arg constructor required by JPA. */
    protected RetroFormatColumn() {
    }

    /**
     * Creates a fully specified column.
     *
     * @param key         server-generated slug, unique within the owning format
     * @param label       human-readable column label
     * @param color       hex color code
     * @param description optional description, may be {@code null}
     * @param icon        optional icon identifier, may be {@code null}
     */
    public RetroFormatColumn(
            final String key, final String label, final String color,
            final String description, final String icon) {
        this.key = key;
        this.label = label;
        this.color = color;
        this.description = description;
        this.icon = icon;
    }

    /**
     * Returns the column's slug key.
     *
     * @return the key
     */
    public String getKey() {
        return key;
    }

    /**
     * Returns the column label.
     *
     * @return the label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Returns the column's color.
     *
     * @return the hex color code
     */
    public String getColor() {
        return color;
    }

    /**
     * Returns the optional column description.
     *
     * @return the description, or {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the optional column icon.
     *
     * @return the icon identifier, or {@code null}
     */
    public String getIcon() {
        return icon;
    }
}
