package com.bitesite.dao;

import com.bitesite.model.OtpChannel;
import com.bitesite.model.OtpCode;

import java.util.Optional;

public interface OtpCodeDao {
    OtpCode save(OtpCode code);

    Optional<OtpCode> findLatest(Long userId, OtpChannel channel);

    void incrementAttempts(Long id);

    void deleteByUserIdAndChannel(Long userId, OtpChannel channel);
}
