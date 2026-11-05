package com.example.paging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaginationTest {

    @Test
    void evenlyDivisibleTotals() {
        assertEquals(10, Pagination.totalPages(100, 10));
    }

    @Test
    void offsetOfSecondPage() {
        assertEquals(10, Pagination.offset(2, 10));
    }

    @Test
    void rejectsNonPositivePageSize() {
        assertThrows(IllegalArgumentException.class, () -> Pagination.totalPages(10, 0));
    }
}
