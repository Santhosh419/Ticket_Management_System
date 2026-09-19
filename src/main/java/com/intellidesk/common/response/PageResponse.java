package com.intellidesk.common.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable pagination envelope.
 *
 * <p>Why not return Spring's {@link Page} directly? Serializing PageImpl is
 * explicitly discouraged (its JSON shape depends on Spring Data internals and
 * can change between versions). This DTO freezes the contract clients see:
 * content plus plain pagination metadata.</p>
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }
}
