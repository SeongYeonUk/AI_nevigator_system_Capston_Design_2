package com.rabbit.domain.user.controller;

import com.rabbit.domain.user.dto.LoginRequest;
import com.rabbit.domain.user.dto.SignUpRequest;
import com.rabbit.domain.user.dto.TokenResponse;
import com.rabbit.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.rabbit.domain.user.dto.DeleteAccountRequest;
import com.rabbit.domain.user.dto.ProfileResponse;
import com.rabbit.domain.user.dto.UpdateProfileRequest;

@RestController
@RequestMapping("/api/auth") // 모든 요청은 /api/auth로 시작합니다.
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    @GetMapping("/profile")
    public ResponseEntity<ProfileResponse> profile(@RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(userService.getProfile(authorization));
    }

    @PutMapping("/profile")
    public ResponseEntity<String> updateProfile(
            @RequestHeader("Authorization") String authorization,
            @RequestBody UpdateProfileRequest request
    ) {
        userService.updateProfile(authorization, request);
        return ResponseEntity.ok("회원정보가 수정되었습니다.");
    }

    @DeleteMapping("/account")
    public ResponseEntity<String> deleteAccount(
            @RequestHeader("Authorization") String authorization,
            @RequestBody DeleteAccountRequest request
    ) {
        userService.deleteAccount(authorization, request);
        return ResponseEntity.ok("회원탈퇴가 완료되었습니다.");
    }

    /**
     * 회원가입 API
     * POST /api/auth/signup
     */
    @PostMapping("/signup")
    public ResponseEntity<String> signup(@RequestBody SignUpRequest request) {
        userService.signUp(request);
        return ResponseEntity.ok("회원가입이 완료되었습니다.");
    }

    /**
     * 로그인 API
     * POST /api/auth/login
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest request) {
        String token = userService.login(request);
        return ResponseEntity.ok(new TokenResponse(token));
    }
}