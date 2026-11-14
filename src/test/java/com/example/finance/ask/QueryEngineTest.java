package com.example.finance.ask;

import com.example.finance.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.example.finance.Fixtures.SMALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryEngineTest {
    private final QueryEngine engine = new QueryEngine();

    private static QuerySpec spec(QuerySpec.Metric metric, TransactionType type, List<String> categories,
                                  QuerySpec.GroupBy groupBy, int limit) {
        return new QuerySpec(metric, type, categories, null, null, null, null, null, null, groupBy, true, limit);
    }

    @Test
    void sumOfSpendingLeavesIncomeOut() {
        QueryEngine.Result r = engine.execute(spec(QuerySpec.Metric.SUM, TransactionType.DEBIT, List.of(), QuerySpec.GroupBy.NONE, 10), SMALL);
        assertEquals(1490.75, r.value(), 0.001);
        assertEquals(5L, r.matched());
    }

    @Test
    void categoryAndDateRangeFiltersAreInclusive() {
        QuerySpec s = new QuerySpec(QuerySpec.Metric.SUM, TransactionType.DEBIT, List.of("groceries"), null, null,
                LocalDate.parse("2025-01-05"), LocalDate.parse("2025-01-20"), null, null, QuerySpec.GroupBy.NONE, true, 10);
        QueryEngine.Result r = engine.execute(s, SMALL);
        assertEquals(150.00, r.value(), 0.001);
        assertEquals(2L, r.matched());
    }

    @Test
    void merchantAndTextMatchIgnoringCase() {
        QuerySpec byMerchant = new QuerySpec(QuerySpec.Metric.COUNT, null, List.of(), "fresh market", null, null, null, null, null,
                QuerySpec.GroupBy.NONE, true, 10);
        QuerySpec byText = new QuerySpec(QuerySpec.Metric.COUNT, null, List.of(), null, "DINNER", null, null, null, null,
                QuerySpec.GroupBy.NONE, true, 10);
        assertEquals(3.0, engine.execute(byMerchant, SMALL).value(), 0.001);
        assertEquals(1.0, engine.execute(byText, SMALL).value(), 0.001);
    }

    @Test
    void amountBoundsAreInclusive() {
        QuerySpec s = new QuerySpec(QuerySpec.Metric.COUNT, TransactionType.DEBIT, List.of(), null, null, null, null, 70.0, 95.25,
                QuerySpec.GroupBy.NONE, true, 10);
        assertEquals(3.0, engine.execute(s, SMALL).value(), 0.001);
    }

    @Test
    void maxReturnsTheTransactionItself() {
        QueryEngine.Result r = engine.execute(spec(QuerySpec.Metric.MAX, TransactionType.DEBIT, List.of(), QuerySpec.GroupBy.NONE, 10), SMALL);
        assertEquals(1200.00, r.value(), 0.001);
        assertEquals(1, r.rows().size());
        assertEquals("Landlord LLC", r.rows().get(0).destination());
    }

    @Test
    void monthGroupsStayInCalendarOrder() {
        QueryEngine.Result r = engine.execute(spec(QuerySpec.Metric.SUM, TransactionType.DEBIT, List.of(), QuerySpec.GroupBy.MONTH, 50), SMALL);
        assertEquals(List.of("2025-01", "2025-02", "2025-03"), r.groups().stream().map(QueryEngine.Group::key).toList());
        assertEquals(1295.25, r.groups().get(2).value(), 0.001);
    }

    @Test
    void categoryGroupsSortByValueAndRespectTheLimit() {
        QueryEngine.Result r = engine.execute(spec(QuerySpec.Metric.SUM, TransactionType.DEBIT, List.of(), QuerySpec.GroupBy.CATEGORY, 2), SMALL);
        assertEquals(List.of("Housing", "Groceries"), r.groups().stream().map(QueryEngine.Group::key).toList());
        assertEquals(245.25, r.groups().get(1).value(), 0.001);
    }

    @Test
    void listReturnsLargestFirstUpToTheLimit() {
        QueryEngine.Result r = engine.execute(spec(QuerySpec.Metric.LIST, TransactionType.DEBIT, List.of(), QuerySpec.GroupBy.NONE, 2), SMALL);
        assertEquals(2, r.rows().size());
        assertEquals(1200.00, r.rows().get(0).amount(), 0.001);
        assertEquals(95.25, r.rows().get(1).amount(), 0.001);
        assertTrue(r.matched() > r.rows().size());
    }

    @Test
    void averageOfNothingIsZeroNotNaN() {
        QuerySpec s = new QuerySpec(QuerySpec.Metric.AVERAGE, null, List.of(), "Nobody", null, null, null, null, null,
                QuerySpec.GroupBy.NONE, true, 10);
        QueryEngine.Result r = engine.execute(s, SMALL);
        assertEquals(0.0, r.value(), 0.0);
        assertEquals(0L, r.matched());
    }
}
