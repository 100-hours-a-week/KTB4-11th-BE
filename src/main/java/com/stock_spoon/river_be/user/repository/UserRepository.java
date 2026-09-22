package com.stock_spoon.river_be.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.stock_spoon.river_be.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
}
