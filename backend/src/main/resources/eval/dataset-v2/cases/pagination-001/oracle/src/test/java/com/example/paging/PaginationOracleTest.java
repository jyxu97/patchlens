package com.example.paging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaginationOracleTest {

    @Test
    void partialLastPageIsCounted() {
        assertEquals(11, Pagination.totalPages(101, 10));
        assertEquals(1, Pagination.totalPages(1, 10));
    }

    @Test
    void emptyAndExactTotals() {
        assertEquals(0, Pagination.totalPages(0, 10));
        assertEquals(10, Pagination.totalPages(100, 10));
    }
}
