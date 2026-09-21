package com.stock_spoon.river_be.user.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import com.stock_spoon.river_be.user.entity.OAuthProvider;
import com.stock_spoon.river_be.user.entity.UserOAuth;

public interface UserOAuthRepository extends JpaRepository<UserOAuth, Long> {
    @EntityGraph(attributePaths = "user")
    Optional<UserOAuth> findByProviderAndProviderUserId(
            OAuthProvider provider, long providerUserId);
}
