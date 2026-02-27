package com.rabbit.domain.user.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdateProfileRequest {
    private String nickname;
    private String currentPassword;
    private String newPassword;
}