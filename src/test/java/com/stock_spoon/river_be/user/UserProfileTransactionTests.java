package com.stock_spoon.river_be.user;

import com.stock_spoon.river_be.user.dto.UserProfileUpdateRequest;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.exception.UserNotFoundException;
import com.stock_spoon.river_be.user.repository.UserRepository;
import com.stock_spoon.river_be.user.service.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

// 요청 외부에 테스트 트랜잭션을 두지 않아 실제 커밋·롤백·행 잠금을 검증한다.
@SpringBootTest
class UserProfileTransactionTests {
    @MockitoSpyBean UserRepository users;
    @MockitoBean ProfileImageService images;
    @Autowired UserService service;
    @Autowired UserWithdrawalService withdrawal;
    @Autowired jakarta.persistence.EntityManager em;
    long id;

    @BeforeEach void setup() { id = users.saveAndFlush(new User("기존", "https://example.com/old.png")).getId(); }
    @AfterEach void cleanup() { users.deleteById(id); }

    @Test void commitFailureRollsBackBothFields() {
        when(images.prepare(id, "issued")).thenReturn("profile-images/final/123/new.png");
        doAnswer(call -> {
            var result = java.util.Optional.ofNullable(em.find(User.class, id, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    em.flush();
                    throw new DataAccessResourceFailureException("commit failure");
                }
            });
            return result;
        }).when(users).findLockedById(id);
        assertThatThrownBy(() -> service.updateMe(id, new UserProfileUpdateRequest(true, "변경", true, "issued")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        var saved = users.findById(id).orElseThrow();
        assertThat(saved.getNickname()).isEqualTo("기존");
        assertThat(saved.getProfileImageRef()).isEqualTo("https://example.com/old.png");
    }

    @Test void concurrentDisjointUpdatesKeepBothChanges() throws Exception {
        when(images.prepare(id, "issued")).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return "profile-images/final/123/new.png";
        });
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var nickname = executor.submit(() -> {
                start.await(); service.updateMe(id, new UserProfileUpdateRequest(true, "변경", false, null)); return null;
            });
            var image = executor.submit(() -> {
                start.await(); service.updateMe(id, new UserProfileUpdateRequest(false, null, true, "issued")); return null;
            });
            start.countDown();
            nickname.get(10, TimeUnit.SECONDS);
            image.get(10, TimeUnit.SECONDS);
        }
        var saved = users.findById(id).orElseThrow();
        assertThat(saved.getNickname()).isEqualTo("변경");
        assertThat(saved.getProfileImageRef()).isEqualTo("profile-images/final/123/new.png");
    }

    @Test void withdrawalDuringImagePreparationCannotRecreateUser() throws Exception {
        var preparing = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        when(images.prepare(id, "issued")).thenAnswer(call -> {
            preparing.countDown();
            if (!resume.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return "profile-images/final/123/new.png";
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var update = executor.submit(() -> service.updateMe(id, new UserProfileUpdateRequest(true, "변경", true, "issued")));
            try {
                assertThat(preparing.await(10, TimeUnit.SECONDS)).isTrue();
                withdrawal.withdraw(id);
            } finally {
                resume.countDown();
            }
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(UserNotFoundException.class);
        }
        assertThat(users.findById(id)).isEmpty();
    }
}
