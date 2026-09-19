package com.intellidesk.user.repository;

import com.intellidesk.user.domain.Role;
import com.intellidesk.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findByRoleAndActiveTrue(Role role);
}
