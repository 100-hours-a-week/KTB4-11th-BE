package com.stock_spoon.river_be.user.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.stock_spoon.river_be.user.dto.UserMeResponse;
import com.stock_spoon.river_be.user.dto.UserProfileUpdateRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.stock_spoon.river_be.user.exception.UserNotFoundException;
import com.stock_spoon.river_be.user.repository.UserRepository;

@Service
public class UserService {
    private final UserRepository repository;
    private final ProfileImageService images;
    private final TransactionTemplate tx;

    public UserService(UserRepository repository, ProfileImageService images, PlatformTransactionManager manager) {
        this.repository = repository;
        this.images = images;
        this.tx = new TransactionTemplate(manager);
    }

    @Transactional(readOnly = true)
    public UserMeResponse getMe(long userId) {
        var user = repository.findById(userId).orElseThrow(UserNotFoundException::new);
        return new UserMeResponse(user.getNickname(), images.resolveUrl(user.getProfileImageRef()), user.isOnboardingCompleted());
    }

    public void updateMe(long userId, UserProfileUpdateRequest request) {
        String finalKey = request.hasImage() && request.imageKey() != null
                ? images.prepare(userId, request.imageKey()) : null;
        tx.executeWithoutResult(status -> {
            var user = repository.findLockedById(userId).orElseThrow(UserNotFoundException::new);
            if (request.hasNickname()) user.updateNickname(request.nickname());
            if (request.hasImage()) {
                if (finalKey == null) user.clearProfileImage();
                else user.replaceProfileImage(finalKey);
            }
        });
    }
}
