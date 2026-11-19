package com.apisentinel.shared;

import java.util.List;
import org.springframework.http.HttpStatus;

public record Paging(int page, int size) {
    public Paging {
        if (page < 0 || page > 100_000 || size < 1 || size > 100) {
            throw new Problem(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be between 0 and 100000 and size between 1 and 100.");
        }
    }

    public long offset() { return (long) page * size; }

    public <T> Page<T> slice(List<T> rows) {
        return new Page<>(List.copyOf(rows.subList(0, Math.min(rows.size(), size))), page, size, rows.size() > size);
    }

    public record Page<T>(List<T> items, int page, int size, boolean hasNext) {}
}
