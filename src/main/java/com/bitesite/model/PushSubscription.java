package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushSubscription {
    private Long id;
    private Long userId;
    private String endpoint;
    private String p256dhKey;
    private String authKey;
    private LocalDateTime createdAt;
}
