package fr.pivot.agilite.retro.format;

import fr.pivot.agilite.retro.format.dto.CreateRetroFormatColumnRequest;
import fr.pivot.agilite.retro.format.dto.CreateRetroFormatRequest;
import fr.pivot.agilite.retro.format.dto.RetroFormatListResponse;
import fr.pivot.agilite.retro.format.dto.RetroFormatResponse;
import fr.pivot.agilite.retro.session.RetroFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Business logic for the retrospective format catalogue: listing the 4 system formats plus a
 * tenant's custom formats, and creating new tenant-owned custom formats (US20.2.1).
 */
@Service
public class RetroFormatService {

    /**
     * Default color palette assigned by column position (0-indexed) when a custom format's
     * column omits {@code color} — exactly 8 entries, covering the full 2-8 column range with no
     * wraparound in practice; the modulo below is kept for defensive safety only.
     */
    static final List<String> DEFAULT_COLUMN_COLORS = List.of(
            "#2E7D32", "#C62828", "#1565C0", "#EF6C00", "#6A1B9A", "#00838F", "#AD1457", "#455A64");

    /** Fallback slug used when a column label contains no ASCII alphanumeric character at all. */
    private static final String FALLBACK_SLUG = "COLUMN";

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Z0-9]+");
    private static final Pattern LEADING_UNDERSCORES = Pattern.compile("^_+");
    private static final Pattern TRAILING_UNDERSCORES = Pattern.compile("_+$");

    private final RetroCustomFormatRepository customFormatRepository;

    /**
     * Constructs the service with its required persistence dependency.
     *
     * @param customFormatRepository custom-format persistence
     */
    public RetroFormatService(final RetroCustomFormatRepository customFormatRepository) {
        this.customFormatRepository = customFormatRepository;
    }

    /**
     * Lists the format catalogue for a tenant: the 4 system formats (always present, fixed
     * order), followed by the tenant's own custom formats, oldest first. Never includes another
     * tenant's custom formats.
     *
     * @param tenantId the caller's tenant id, extracted exclusively from the resolved bearer
     *                 token
     * @return the ordered catalogue
     */
    @Transactional(readOnly = true)
    public RetroFormatListResponse listFormats(final Long tenantId) {
        List<RetroFormatResponse> formats = new ArrayList<>();
        for (RetroFormat format : RetroFormatCatalog.systemFormats()) {
            formats.add(RetroFormatResponse.forSystemFormat(format));
        }
        customFormatRepository.findByTenantIdOrderByCreatedAtAsc(tenantId).stream()
                .map(RetroFormatResponse::from)
                .forEach(formats::add);
        return new RetroFormatListResponse(formats);
    }

    /**
     * Creates a new tenant-owned custom format.
     *
     * <p>Column {@code key}s are always server-generated: an uppercase ASCII slug of the
     * column's label, disambiguated with a numeric suffix ({@code _2}, {@code _3}, ...) on
     * collision within the same format. A column that omits {@code color} is assigned the
     * default palette entry at its 0-indexed position; an omitted {@code description}/{@code
     * icon} stays {@code null}.
     *
     * @param request  the validated creation request (2 to 8 columns, enforced by Bean
     *                 Validation on the DTO)
     * @param callerId the authenticated caller's {@code public.users.id}
     * @param tenantId the authenticated caller's tenant id, extracted exclusively from the
     *                 resolved bearer token — never from the request body
     * @return the created format, in the same shape as a {@code GET /retro/formats} entry
     */
    @Transactional
    public RetroFormatResponse createFormat(
            final CreateRetroFormatRequest request, final Long callerId, final Long tenantId) {
        List<RetroFormatColumn> columns = buildColumns(request.columns());
        RetroCustomFormat format = new RetroCustomFormat(tenantId, request.label(), callerId, columns);
        return RetroFormatResponse.from(customFormatRepository.save(format));
    }

    private static List<RetroFormatColumn> buildColumns(final List<CreateRetroFormatColumnRequest> requests) {
        List<RetroFormatColumn> columns = new ArrayList<>(requests.size());
        Map<String, Integer> occurrences = new HashMap<>();
        for (int position = 0; position < requests.size(); position++) {
            CreateRetroFormatColumnRequest columnRequest = requests.get(position);
            String key = uniqueKey(slugify(columnRequest.label()), occurrences);
            String color = columnRequest.color() != null
                    ? columnRequest.color()
                    : DEFAULT_COLUMN_COLORS.get(position % DEFAULT_COLUMN_COLORS.size());
            columns.add(new RetroFormatColumn(
                    key, columnRequest.label(), color, columnRequest.description(), columnRequest.icon()));
        }
        return columns;
    }

    /**
     * Builds an uppercase ASCII slug from a column label: non-alphanumeric runs (spaces,
     * punctuation, accents once uppercased) collapse to a single {@code _}, and any leading or
     * trailing {@code _} left by a label starting/ending with such a run is stripped.
     *
     * @param label the raw column label
     * @return the slug, never blank — falls back to {@value #FALLBACK_SLUG} if the label
     *     contains no ASCII alphanumeric character at all
     */
    static String slugify(final String label) {
        String upper = label.toUpperCase(Locale.ROOT);
        String slug = NON_ALPHANUMERIC.matcher(upper).replaceAll("_");
        slug = LEADING_UNDERSCORES.matcher(slug).replaceAll("");
        slug = TRAILING_UNDERSCORES.matcher(slug).replaceAll("");
        return slug.isEmpty() ? FALLBACK_SLUG : slug;
    }

    /**
     * Disambiguates {@code baseKey} against keys already assigned within the same format, by
     * appending a numeric suffix on collision.
     *
     * @param baseKey     the candidate slug
     * @param occurrences running per-format count of each base slug seen so far (mutated)
     * @return {@code baseKey} unchanged if this is its first occurrence, otherwise {@code
     *     baseKey + "_" + n}
     */
    private static String uniqueKey(final String baseKey, final Map<String, Integer> occurrences) {
        int count = occurrences.merge(baseKey, 1, Integer::sum);
        return count == 1 ? baseKey : baseKey + "_" + count;
    }
}
