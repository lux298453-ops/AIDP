package com.example.aidocumentplatform.repository;

import com.example.aidocumentplatform.model.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldSaveAndFindUser() {
        // Given: 构建一个用户
        User user = User.builder()
                .username("testuser")
                .password("$2a$10$encryptedPasswordHash1234567890")
                .nickname("测试用户")
                .build();

        // When: 插入
        User saved = userRepository.save(user);

        // Then: 插入后应有 ID 和时间戳
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUsername()).isEqualTo("testuser");
        assertThat(saved.getNickname()).isEqualTo("测试用户");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        // When: 通过 username 查询
        Optional<User> found = userRepository.findByUsername("testuser");

        // Then: 能查出来，且字段一致
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getPassword()).isEqualTo(saved.getPassword());
        assertThat(found.get().getNickname()).isEqualTo("测试用户");
    }

    @Test
    void shouldCheckUsernameExists() {
        // Given: 已有用户
        userRepository.save(User.builder()
                .username("existing")
                .password("pwd")
                .nickname("已存在")
                .build());

        // Then: existsByUsername 正确
        assertThat(userRepository.existsByUsername("existing")).isTrue();
        assertThat(userRepository.existsByUsername("notfound")).isFalse();
    }

    @Test
    void shouldNotFindDeletedUser() {
        // Given: 插入后删除
        User user = userRepository.save(User.builder()
                .username("todelete")
                .password("pwd")
                .nickname("待删")
                .build());
        userRepository.delete(user);

        // Then: 查不到
        assertThat(userRepository.findByUsername("todelete")).isEmpty();
    }
}
