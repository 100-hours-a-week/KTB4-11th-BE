package com.stock_spoon.river_be.account.entity;

import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.stock_spoon.river_be.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "accounts", uniqueConstraints =
        @UniqueConstraint(name = "uk_account_user_name", columnNames = {"user_id", "name"}))
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String name;

    @Column(name = "initial_capital", nullable = false)
    private long initialCapital;

    @Column(name = "cash_balance", nullable = false)
    private long cashBalance;

    @Column(name = "ai_delegated", nullable = false)
    private boolean aiDelegated;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
    }

    public Account(User user, String name, long initialCapital) {
        this.user = user;
        this.name = name;
        this.initialCapital = initialCapital;
        this.cashBalance = initialCapital;
        this.aiDelegated = true;
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getInitialCapital() {
        return initialCapital;
    }

    public long getCashBalance() {
        return cashBalance;
    }

    public boolean isAiDelegated() {
        return aiDelegated;
    }

    public boolean isActive() {
        return active;
    }

    public void rename(String name) {
        this.name = name;
    }
}
