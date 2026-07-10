package fr.pivot.agilite.wheel;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link WheelDraw} entities (US14.2.1).
 */
public interface WheelDrawRepository extends JpaRepository<WheelDraw, UUID> {

    /**
     * Finds the most recent draws of a wheel, most recent first, limited by the given
     * {@link Pageable} (used to cap the result to {@code limit} rows — see
     * {@code GET /wheels/{wheelId}/draws}).
     *
     * @param wheelId  the wheel's identifier
     * @param pageable a page request whose size encodes the desired limit (page index always 0)
     * @return the most recent draws, most recent first
     */
    List<WheelDraw> findByWheelIdOrderByDrawnAtDesc(UUID wheelId, Pageable pageable);
}
