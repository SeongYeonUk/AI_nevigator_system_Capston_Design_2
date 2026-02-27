package com.rabbit.domain.user.service;

import com.rabbit.domain.user.dto.LoginRequest;
import com.rabbit.domain.user.dto.SignUpRequest;
import com.rabbit.domain.user.entity.User;
import com.rabbit.domain.user.repository.UserRepository;
import com.rabbit.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.rabbit.domain.user.dto.ProfileResponse;
import com.rabbit.domain.user.dto.UpdateProfileRequest;
import com.rabbit.domain.user.dto.DeleteAccountRequest;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public ProfileResponse getProfile(String rawToken) {
        User user = findUserByToken(rawToken);
        return new ProfileResponse(user.getLoginId(), user.getNickname());
    }

    @Transactional
    public void updateProfile(String rawToken, UpdateProfileRequest request) {
        User user = findUserByToken(rawToken);

        if (request.getNickname() != null && !request.getNickname().isBlank()) {
            user.changeNickname(request.getNickname().trim());
        }

        boolean hasNewPassword = request.getNewPassword() != null && !request.getNewPassword().isBlank();
        if (hasNewPassword) {
            String currentPassword = request.getCurrentPassword() == null ? "" : request.getCurrentPassword();
            if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
                throw new IllegalArgumentException("현재 비밀번호가 일치하지 않습니다.");
            }
            user.changePassword(passwordEncoder.encode(request.getNewPassword()));
        }
    }

    @Transactional
    public void deleteAccount(String rawToken, DeleteAccountRequest request) {
        User user = findUserByToken(rawToken);
        String password = request.getPassword() == null ? "" : request.getPassword();

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        userRepository.delete(user);
    }

    private User findUserByToken(String rawToken) {
        String token = stripBearer(rawToken);
        if (!jwtTokenProvider.validateToken(token)) {
            throw new IllegalArgumentException("유효하지 않은 토큰입니다.");
        }

        String loginId = jwtTokenProvider.getLoginId(token);
        return userRepository.findByLoginId(loginId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
    }

    private String stripBearer(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("인증 토큰이 없습니다.");
        }
        if (rawToken.startsWith("Bearer ")) {
            return rawToken.substring(7).trim();
        }
        return rawToken.trim();
    }

    // 회원가입
    @Transactional
    public Long signUp(SignUpRequest request) {

        // 아이디 중복 확인
        if (userRepository.existsByLoginId(request.getLoginId())) {
            throw new IllegalArgumentException("이미 존재하는 아이디입니다.");
        }

        // 유저 생성 및 비밀번호 암호화
        User user = User.builder()
                .loginId(request.getLoginId())
                .password(passwordEncoder.encode(request.getPassword()))
                .nickname(request.getNickname())
                .build();

        return userRepository.save(user).getId();
    }


    public String login(LoginRequest request) {
        // 1. 아이디로 유저 조회
        User user = userRepository.findByLoginId(request.getLoginId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 아이디입니다."));

        // 2. 비밀번호 일치 여부 확인 (암호화된 비번 비교)
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        // 3. 성공 시 JWT 토큰 생성 및 반환
        return jwtTokenProvider.createToken(user.getLoginId());
    }
}