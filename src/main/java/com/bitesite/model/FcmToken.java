package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One FCM registration token: a single install of a native app, signed in as one user.
 *
 * <p>The native counterpart to {@link PushSubscription}. A user can hold rows in both —
 * the phone app and a desktop browser are different devices and both should ring.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FcmToken {
    private Long id;
    private Long userId;
    private String token;
    private String platform;
    private LocalDateTime createdAt;
}
