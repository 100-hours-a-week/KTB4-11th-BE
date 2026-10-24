package com.stock_spoon.river_be.user.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.stock_spoon.river_be.user.dto.UserMeResponse;
import com.stock_spoon.river_be.user.exception.UserNotFoundException;
import com.stock_spoon.river_be.user.repository.UserRepository;

@Service
public class UserService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserService.class);
    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public UserMeResponse getMe(long userId) {
        var user = repository.findById(userId).orElseThrow(() -> {
            log.warn("event=user_lookup_rejected stage=current_user_lookup userId={} code=USER_NOT_FOUND", userId);
            return new UserNotFoundException();
        });
        return new UserMeResponse(user.getNickname(), user.getProfileImageUrl(), user.isOnboardingCompleted());
    }
}
