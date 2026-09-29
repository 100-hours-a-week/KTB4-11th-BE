package com.stock_spoon.river_be.user.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.stock_spoon.river_be.user.dto.UserMeResponse;
import com.stock_spoon.river_be.user.exception.UserNotFoundException;
import com.stock_spoon.river_be.user.repository.UserRepository;

@Service
public class UserService {
    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public UserMeResponse getMe(long userId) {
        var user = repository.findById(userId).orElseThrow(UserNotFoundException::new);
        return new UserMeResponse(user.getNickname(), user.getProfileImageUrl());
    }
}
