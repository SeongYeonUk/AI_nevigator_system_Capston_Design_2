package com.rabbit.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SignUpRequest
{
    @NotBlank(message = "아이디 미입력")
    private String loginId;

    @NotBlank(message = "비밀번호 미입력")
    private String password;

    @NotBlank(message = "닉네임 미입력")
    private String nickname;
}