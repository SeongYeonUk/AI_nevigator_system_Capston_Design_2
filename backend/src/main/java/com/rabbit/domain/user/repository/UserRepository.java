package com.rabbit.domain.user.repository;

import com.rabbit.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    // 로그인 및 아이디 중복 확인을 위해 사용합니다.
    Optional<User> findByLoginId(String loginId);
    boolean existsByLoginId(String loginId);
}