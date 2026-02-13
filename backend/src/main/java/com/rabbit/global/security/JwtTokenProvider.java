package com.rabbit.global.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtTokenProvider {

    // application.yml에 정의한 비밀키와 만료시간을 가져옵니다.
    @Value("${jwt.secret}")
    private String secretKey;

    @Value("${jwt.expiration}")
    private long validityInMilliseconds;

    private Key key;

    @PostConstruct
    protected void init() {
        // 비밀키를 Base64로 인코딩하여 HMAC-SHA 알고리즘에 적합한 키로 변환합니다.
        byte[] keyBytes = Base64.getEncoder().encode(secretKey.getBytes());
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 유저의 loginId를 받아 토큰을 생성합니다.
     *
     */
    public String createToken(String loginId) {
        Claims claims = Jwts.claims().setSubject(loginId);
        // 필요하다면 여기에 claims.put("role", "USER") 처럼 추가 정보를 넣을 수 있습니다.

        Date now = new Date();
        Date validity = new Date(now.getTime() + validityInMilliseconds);

        return Jwts.builder()
                .setClaims(claims) // 데이터(아이디 등)
                .setIssuedAt(now)  // 발행 시간
                .setExpiration(validity) // 만료 시간
                .signWith(key, SignatureAlgorithm.HS256) // 암호화 알고리즘
                .compact();
    }

    /**
     * 토큰에서 유저의 loginId(Subject)를 추출합니다.
     */
    public String getLoginId(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    /**
     * 토큰이 유효한지(만료되지 않았는지, 변조되지 않았는지) 확인합니다.
     */
    public boolean validateToken(String token) {
        try {
            Jws<Claims> claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token);
            return !claims.getBody().getExpiration().before(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            // 유효하지 않은 토큰일 경우 false 반환
            return false;
        }
    }
}