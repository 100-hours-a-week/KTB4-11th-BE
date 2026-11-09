package com.stock_spoon.river_be.user.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import com.stock_spoon.river_be.user.entity.OAuthProvider;
import com.stock_spoon.river_be.user.entity.UserOAuth;

public interface UserOAuthRepository extends JpaRepository<UserOAuth, Long> {
    // [회원 탈퇴 추가] 내부 userId로 연결 정보를 찾아 카카오 unlink에 필요한 providerUserId를 얻는다.
    Optional<UserOAuth> findByUserId(long userId);
    @EntityGraph(attributePaths = "user")
    Optional<UserOAuth> findByProviderAndProviderUserId(
            OAuthProvider provider, long providerUserId);
}
