package com.stock_spoon.river_be.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(nullable = false)
    private String nickname;

    @Column(name = "profile_image_url", length = 2048)
    private String profileImageUrl;

    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted;

    protected User() {
    }

    public User(String nickname) {
        this(nickname, null);
    }

    public User(String nickname, String profileImageUrl) {
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
    }

    public Long getId() {
        return id;
    }

    public String getNickname() {
        return nickname;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public boolean isOnboardingCompleted() {
        return onboardingCompleted;
    }

    public void completeOnboarding() {
        this.onboardingCompleted = true;
    }

    public void synchronizeProfile(String nickname, String profileImageUrl) {
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
    }
}
