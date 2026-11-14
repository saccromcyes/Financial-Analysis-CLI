package com.example.finance.ask;

import com.example.finance.model.TransactionType;
import com.example.finance.util.Json;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Model output is untrusted: anything outside the schema must be rejected, not guessed. */
class QuerySpecTest {
    private static final Set<String> CATEGORIES = Set.of("Groceries", "Food & Dining", "Housing");

    @Test
    void readsAFullQueryAndMatchesCategoryCase() {
        QuerySpec s = QuerySpec.fromMap(Json.parseObject("""
                {"metric": "SUM", "type": "debit", "categories": ["groceries"], "from": "2025-03-01", "to": "2025-03-31",
                 "min_amount": "10", "group_by": "month", "sort": "asc", "limit": 5}"""), CATEGORIES);
        assertEquals(QuerySpec.Metric.SUM, s.metric());
        assertEquals(TransactionType.DEBIT, s.type());
        assertEquals(List.of("Groceries"), s.categories());
        assertEquals(LocalDate.of(2025, 3, 31), s.to());
        assertEquals(10.0, s.minAmount(), 0.0);
        assertEquals(QuerySpec.GroupBy.MONTH, s.groupBy());
        assertFalse(s.descending());
        assertEquals(5, s.limit());
    }

    @Test
    void defaultsAreASumOfEverything() {
        QuerySpec s = QuerySpec.fromMap(Map.of(), CATEGORIES);
        assertEquals(QuerySpec.Metric.SUM, s.metric());
        assertEquals(QuerySpec.GroupBy.NONE, s.groupBy());
        assertEquals(10, s.limit());
    }

    @Test
    void rejectsUnknownFields() {
        assertThrows(IllegalArgumentException.class, () -> QuerySpec.fromMap(Map.of("sql", "DROP TABLE"), CATEGORIES));
    }

    @Test
    void rejectsInventedCategories() {
        assertThrows(IllegalArgumentException.class, () -> QuerySpec.fromMap(Map.of("categories", List.of("Yachts")), CATEGORIES));
    }

    @Test
    void rejectsBadValues() {
        assertThrows(IllegalArgumentException.class, () -> QuerySpec.fromMap(Map.of("metric", "median"), CATEGORIES));
        assertThrows(IllegalArgumentException.class, () -> QuerySpec.fromMap(Map.of("sort", "sideways"), CATEGORIES));
        assertThrows(IllegalArgumentException.class, () -> QuerySpec.fromMap(Map.of("from", "March"), CATEGORIES));
        assertThrows(IllegalArgumentException.class, () -> QuerySpec.fromMap(Map.of("from", "2025-04-01", "to", "2025-03-01"), CATEGORIES));
    }

    @Test
    void clampsTheLimitFromModelOutput() {
        assertEquals(50, QuerySpec.fromMap(Map.of("limit", 1000), CATEGORIES).limit());
        assertEquals(1, QuerySpec.fromMap(Map.of("limit", 0), CATEGORIES).limit());
    }

    @Test
    void roundTripsThroughItsMap() {
        QuerySpec original = QuerySpec.fromMap(Map.of("metric", "count", "categories", List.of("Housing"), "group_by", "merchant"), CATEGORIES);
        QuerySpec again = QuerySpec.fromMap(Json.parseObject(Json.write(original.toMap())), CATEGORIES);
        assertEquals(original, again);
    }
}
