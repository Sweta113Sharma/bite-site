package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** A single issued OTP. {@code codeHash} is a SHA-256 hex digest, never the plaintext code
 * — verification re-hashes the submitted guess and compares. {@code attempts} counts wrong
 * guesses against this specific code so a request for a fresh code (which deletes this row)
 * is the only way to reset it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OtpCode {
    private Long id;
    private Long userId;
    private OtpChannel channel;
    private String codeHash;
    private LocalDateTime expiresAt;
    @Builder.Default
    private int attempts = 0;
    private LocalDateTime createdAt;
}
