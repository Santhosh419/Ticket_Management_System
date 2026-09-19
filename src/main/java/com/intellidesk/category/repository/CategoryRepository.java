package com.intellidesk.category.repository;

import com.intellidesk.category.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByCode(String code);

    boolean existsByCode(String code);

    List<Category> findAllByActiveTrueOrderByCodeAsc();
}
