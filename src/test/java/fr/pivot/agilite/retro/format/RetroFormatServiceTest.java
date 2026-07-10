package fr.pivot.agilite.retro.format;

import fr.pivot.agilite.retro.format.dto.CreateRetroFormatColumnRequest;
import fr.pivot.agilite.retro.format.dto.CreateRetroFormatRequest;
import fr.pivot.agilite.retro.format.dto.RetroFormatListResponse;
import fr.pivot.agilite.retro.format.dto.RetroFormatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetroFormatService}: catalogue listing, slug generation/disambiguation,
 * and default color assignment (US20.2.1).
 *
 * <p>All external dependencies are mocked via Mockito. No Spring context is loaded.
 */
@ExtendWith(MockitoExtension.class)
class RetroFormatServiceTest {

    @Mock
    private RetroCustomFormatRepository customFormatRepository;

    private RetroFormatService service;

    private static final Long TENANT_A = 100L;
    private static final Long CALLER_ID = 1L;

    /** Initialises the service under test with mocked dependencies. */
    @BeforeEach
    void setUp() {
        service = new RetroFormatService(customFormatRepository);
    }

    // -------------------------------------------------------------------------
    // listFormats()
    // -------------------------------------------------------------------------

    /**
     * Given a tenant with no custom formats, when listFormats() is called, then it returns
     * exactly the 4 system formats, in fixed order.
     */
    @Test
    void listFormats_withNoCustomFormats_returnsOnlyTheFourSystemFormats() {
        when(customFormatRepository.findByTenantIdOrderByCreatedAtAsc(TENANT_A)).thenReturn(List.of());

        RetroFormatListResponse response = service.listFormats(TENANT_A);

        assertThat(response.formats()).hasSize(4);
        assertThat(response.formats()).allMatch(RetroFormatResponse::system);
        assertThat(response.formats()).extracting(RetroFormatResponse::key)
                .containsExactly("START_STOP_CONTINUE", "KIF_KAF", "FOUR_L", "MAD_SAD_GLAD");
    }

    /**
     * Given a tenant with custom formats, when listFormats() is called, then the 4 system
     * formats come first (fixed order), followed by the tenant's own custom formats in creation
     * order — never another tenant's.
     */
    @Test
    void listFormats_withCustomFormats_appendsThemAfterSystemFormatsInOrder() {
        RetroCustomFormat first = withId(new RetroCustomFormat(
                TENANT_A, "First Custom", CALLER_ID,
                List.of(
                        new RetroFormatColumn("A", "A", "#2E7D32", null, null),
                        new RetroFormatColumn("B", "B", "#C62828", null, null))), UUID.randomUUID());
        RetroCustomFormat second = withId(new RetroCustomFormat(
                TENANT_A, "Second Custom", CALLER_ID,
                List.of(
                        new RetroFormatColumn("C", "C", "#2E7D32", null, null),
                        new RetroFormatColumn("D", "D", "#C62828", null, null))), UUID.randomUUID());
        when(customFormatRepository.findByTenantIdOrderByCreatedAtAsc(TENANT_A))
                .thenReturn(List.of(first, second));

        RetroFormatListResponse response = service.listFormats(TENANT_A);

        assertThat(response.formats()).hasSize(6);
        assertThat(response.formats().subList(0, 4)).allMatch(RetroFormatResponse::system);
        assertThat(response.formats().subList(4, 6)).noneMatch(RetroFormatResponse::system);
        assertThat(response.formats().get(4).label()).isEqualTo("First Custom");
        assertThat(response.formats().get(5).label()).isEqualTo("Second Custom");
    }

    // -------------------------------------------------------------------------
    // createFormat()
    // -------------------------------------------------------------------------

    /**
     * Given a request with 2 columns and no explicit colors, when createFormat() is called,
     * then columns get slugged keys and the default palette colors by 0-indexed position, and
     * the persisted entity is saved via the repository.
     */
    @Test
    void createFormat_withoutExplicitColors_assignsDefaultPaletteByPosition() {
        when(customFormatRepository.save(any(RetroCustomFormat.class)))
                .thenAnswer(inv -> withId(inv.getArgument(0), UUID.randomUUID()));

        CreateRetroFormatRequest request = new CreateRetroFormatRequest(
                "Notre format équipe", List.of(
                        new CreateRetroFormatColumnRequest("Bien", null, null, null),
                        new CreateRetroFormatColumnRequest("Mal", null, "Ce qui a mal fonctionné", "thumb_down")));

        RetroFormatResponse response = service.createFormat(request, CALLER_ID, TENANT_A);

        assertThat(response.system()).isFalse();
        assertThat(response.label()).isEqualTo("Notre format équipe");
        assertThat(response.columns()).hasSize(2);
        assertThat(response.columns().get(0).key()).isEqualTo("BIEN");
        assertThat(response.columns().get(0).color()).isEqualTo("#2E7D32");
        assertThat(response.columns().get(0).description()).isNull();
        assertThat(response.columns().get(0).icon()).isNull();
        assertThat(response.columns().get(1).key()).isEqualTo("MAL");
        assertThat(response.columns().get(1).color()).isEqualTo("#C62828");
        assertThat(response.columns().get(1).description()).isEqualTo("Ce qui a mal fonctionné");
        assertThat(response.columns().get(1).icon()).isEqualTo("thumb_down");
    }

    /**
     * Given a column with an explicit {@code color}, when createFormat() is called, then the
     * explicit color is kept, never overridden by the default palette.
     */
    @Test
    void createFormat_withExplicitColor_keepsExplicitColor() {
        when(customFormatRepository.save(any(RetroCustomFormat.class)))
                .thenAnswer(inv -> withId(inv.getArgument(0), UUID.randomUUID()));

        CreateRetroFormatRequest request = new CreateRetroFormatRequest(
                "Format", List.of(
                        new CreateRetroFormatColumnRequest("Un", "#123456", null, null),
                        new CreateRetroFormatColumnRequest("Deux", null, null, null)));

        RetroFormatResponse response = service.createFormat(request, CALLER_ID, TENANT_A);

        assertThat(response.columns().get(0).color()).isEqualTo("#123456");
        assertThat(response.columns().get(1).color()).isEqualTo("#C62828");
    }

    /**
     * Given two columns whose labels slug to the same base key, when createFormat() is called,
     * then the second occurrence is disambiguated with a numeric suffix.
     */
    @Test
    void createFormat_withCollidingLabels_disambiguatesKeys() {
        when(customFormatRepository.save(any(RetroCustomFormat.class)))
                .thenAnswer(inv -> withId(inv.getArgument(0), UUID.randomUUID()));

        CreateRetroFormatRequest request = new CreateRetroFormatRequest(
                "Format", List.of(
                        new CreateRetroFormatColumnRequest("Bien", null, null, null),
                        new CreateRetroFormatColumnRequest("Bien", null, null, null),
                        new CreateRetroFormatColumnRequest("Bien", null, null, null)));

        RetroFormatResponse response = service.createFormat(request, CALLER_ID, TENANT_A);

        assertThat(response.columns()).extracting(c -> c.key())
                .containsExactly("BIEN", "BIEN_2", "BIEN_3");
    }

    /**
     * Given a request with 8 columns (the upper boundary), when createFormat() is called, then
     * every column gets a distinct default color, cycling back to the first palette entry (the
     * defensive modulo, never actually reached within the 2-8 range, but proven here anyway).
     */
    @Test
    void createFormat_withEightColumns_assignsAllEightPaletteColorsInOrder() {
        when(customFormatRepository.save(any(RetroCustomFormat.class)))
                .thenAnswer(inv -> withId(inv.getArgument(0), UUID.randomUUID()));

        List<CreateRetroFormatColumnRequest> columnRequests = List.of(
                new CreateRetroFormatColumnRequest("C1", null, null, null),
                new CreateRetroFormatColumnRequest("C2", null, null, null),
                new CreateRetroFormatColumnRequest("C3", null, null, null),
                new CreateRetroFormatColumnRequest("C4", null, null, null),
                new CreateRetroFormatColumnRequest("C5", null, null, null),
                new CreateRetroFormatColumnRequest("C6", null, null, null),
                new CreateRetroFormatColumnRequest("C7", null, null, null),
                new CreateRetroFormatColumnRequest("C8", null, null, null));
        CreateRetroFormatRequest request = new CreateRetroFormatRequest("Format", columnRequests);

        RetroFormatResponse response = service.createFormat(request, CALLER_ID, TENANT_A);

        assertThat(response.columns()).extracting(c -> c.color())
                .containsExactly(RetroFormatService.DEFAULT_COLUMN_COLORS.toArray(new String[0]));
    }

    // -------------------------------------------------------------------------
    // slugify()
    // -------------------------------------------------------------------------

    /**
     * Given a plain ASCII label, when slugify() is called, then it uppercases it as-is.
     */
    @Test
    void slugify_plainLabel_uppercases() {
        assertThat(RetroFormatService.slugify("Bien")).isEqualTo("BIEN");
    }

    /**
     * Given a label with spaces and punctuation, when slugify() is called, then runs of
     * non-alphanumeric characters collapse to a single underscore.
     */
    @Test
    void slugify_withSpacesAndPunctuation_collapsesToSingleUnderscore() {
        assertThat(RetroFormatService.slugify("Bien   ! joué")).isEqualTo("BIEN_JOU");
    }

    /**
     * Given a label starting or ending with a non-alphanumeric run, when slugify() is called,
     * then the leading/trailing underscore is stripped.
     */
    @Test
    void slugify_withLeadingAndTrailingPunctuation_stripsUnderscores() {
        assertThat(RetroFormatService.slugify("  Bien  ")).isEqualTo("BIEN");
        assertThat(RetroFormatService.slugify("!!!Bien!!!")).isEqualTo("BIEN");
    }

    /**
     * Given a label with no ASCII alphanumeric character at all, when slugify() is called,
     * then it falls back to a fixed non-blank slug rather than producing an empty key.
     */
    @Test
    void slugify_withNoAlphanumericCharacter_fallsBackToFixedSlug() {
        assertThat(RetroFormatService.slugify("!!!")).isEqualTo("COLUMN");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Sets a {@link RetroCustomFormat} instance's id via reflection, simulating a
     * JPA-persisted entity whose id is assigned by {@code @GeneratedValue} — needed because
     * these unit tests mock {@code save()} rather than going through a real Hibernate flush.
     */
    private static RetroCustomFormat withId(final RetroCustomFormat format, final UUID id) {
        try {
            java.lang.reflect.Field field = RetroCustomFormat.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(format, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to set custom format id in test", ex);
        }
        return format;
    }
}
