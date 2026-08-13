package com.bitesite.dao;

import com.bitesite.model.OnboardingLead;
import com.bitesite.model.OnboardingStage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class OnboardingDaoImpl implements OnboardingDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<OnboardingLead> ROW_MAPPER = (rs, rowNum) -> OnboardingLead.builder()
            .id(rs.getLong("id"))
            .tenantId(rs.getObject("tenant_id", Long.class))
            .collegeName(rs.getString("college_name"))
            .contactName(rs.getString("contact_name"))
            .contactEmail(rs.getString("contact_email"))
            .contactPhone(rs.getString("contact_phone"))
            .stage(OnboardingStage.valueOf(rs.getString("stage")))
            .notes(rs.getString("notes"))
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .updatedAt(rs.getTimestamp("updated_at").toLocalDateTime())
            .build();

    @Override
    public OnboardingLead save(OnboardingLead lead) {
        if (lead.getId() == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO onboarding_pipeline (tenant_id, college_name, contact_name, contact_email, "
                                + "contact_phone, stage, notes) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setObject(1, lead.getTenantId());
                ps.setString(2, lead.getCollegeName());
                ps.setString(3, lead.getContactName());
                ps.setString(4, lead.getContactEmail());
                ps.setString(5, lead.getContactPhone());
                ps.setString(6, lead.getStage().name());
                ps.setString(7, lead.getNotes());
                return ps;
            }, keyHolder);
            lead.setId(keyHolder.getKey().longValue());
        } else {
            jdbcTemplate.update(
                    "UPDATE onboarding_pipeline SET college_name = ?, contact_name = ?, contact_email = ?, "
                            + "contact_phone = ?, stage = ?, notes = ? WHERE id = ?",
                    lead.getCollegeName(), lead.getContactName(), lead.getContactEmail(), lead.getContactPhone(),
                    lead.getStage().name(), lead.getNotes(), lead.getId());
        }
        return findById(lead.getId()).orElseThrow();
    }

    @Override
    public Optional<OnboardingLead> findById(Long id) {
        return jdbcTemplate.query("SELECT * FROM onboarding_pipeline WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public List<OnboardingLead> findAll() {
        return jdbcTemplate.query("SELECT * FROM onboarding_pipeline ORDER BY created_at DESC", ROW_MAPPER);
    }

    @Override
    public void updateStage(Long id, OnboardingStage stage) {
        jdbcTemplate.update("UPDATE onboarding_pipeline SET stage = ? WHERE id = ?", stage.name(), id);
    }

    @Override
    public void linkTenant(Long id, Long tenantId) {
        jdbcTemplate.update("UPDATE onboarding_pipeline SET tenant_id = ? WHERE id = ?", tenantId, id);
    }
}
