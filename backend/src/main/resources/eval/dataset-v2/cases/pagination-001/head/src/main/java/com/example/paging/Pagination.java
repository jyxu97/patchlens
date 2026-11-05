package com.example.paging;

/** Pagination helpers for list endpoints. Pages are 1-based. */
public final class Pagination {

    private Pagination() {
    }

    /** Number of pages needed to show {@code totalItems} items, {@code pageSize} per page. */
    public static int totalPages(int totalItems, int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        return totalItems / pageSize;
    }

    /** Zero-based index of the first item on the 1-based {@code page}. */
    public static int offset(int page, int pageSize) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        return (page - 1) * pageSize;
    }
}
