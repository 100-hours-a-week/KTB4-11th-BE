package com.stock_spoon.river_be.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import com.stock_spoon.river_be.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
    // 같은 사용자의 생성 요청은 순서대로 처리해 이름 중복 확인과 저장 사이의 경쟁을 막는다.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id = :id")
    java.util.Optional<User> findLockedById(Long id);

    List<User> findAllByOrderByIdAsc();
}
