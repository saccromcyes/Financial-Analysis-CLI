package com.example.finance.ask;

import com.example.finance.Fixtures;
import com.example.finance.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The keyword fallback, checked against the bundled sample year (2025). */
class RulePlannerTest {
    private static final DatasetContext CTX = DatasetContext.of(Fixtures.sample());
    private final RulePlanner planner = new RulePlanner();

    @Test
    void categoryAndMonth() {
        QuerySpec s = planner.plan("How much did I spend on groceries in March?", CTX);
        assertEquals(QuerySpec.Metric.SUM, s.metric());
        assertEquals(TransactionType.DEBIT, s.type());
        assertEquals(List.of("Groceries"), s.categories());
        assertEquals(LocalDate.of(2025, 3, 1), s.from());
        assertEquals(LocalDate.of(2025, 3, 31), s.to());
    }

    @Test
    void quarterAndSynonym() {
        QuerySpec s = planner.plan("How much did I spend on transport in Q2?", CTX);
        assertEquals(List.of("Transport"), s.categories());
        assertEquals(LocalDate.of(2025, 4, 1), s.from());
        assertEquals(LocalDate.of(2025, 6, 30), s.to());
    }

    @Test
    void lastMonthIsRelativeToTheData() {
        QuerySpec s = planner.plan("How much did I spend on food last month?", CTX);
        assertEquals(LocalDate.of(2025, 11, 1), s.from());
        assertEquals(LocalDate.of(2025, 11, 30), s.to());
    }

    @Test
    void whichCategoryMostIsATopOneGroup() {
        QuerySpec s = planner.plan("Which category did I spend the most on?", CTX);
        assertEquals(QuerySpec.GroupBy.CATEGORY, s.groupBy());
        assertEquals(1, s.limit());
    }

    @Test
    void perMonthCoversEveryMonth() {
        QuerySpec s = planner.plan("How much do I spend on subscriptions per month?", CTX);
        assertEquals(QuerySpec.GroupBy.MONTH, s.groupBy());
        assertEquals(QuerySpec.MAX_LIMIT, s.limit());
    }

    @Test
    void merchantNameIsRecognised() {
        QuerySpec s = planner.plan("How much did I pay StreamFlix in total?", CTX);
        assertEquals("StreamFlix", s.merchant());
        assertNull(s.text());
    }

    @Test
    void specificItemBecomesATextFilter() {
        QuerySpec s = planner.plan("How many coffees did I buy?", CTX);
        assertEquals(QuerySpec.Metric.COUNT, s.metric());
        assertEquals("coffee", s.text());
    }

    @Test
    void amountsAndTopN() {
        QuerySpec over = planner.plan("What did I spend on things over $100 in April?", CTX);
        assertEquals(100.0, over.minAmount(), 0.0);
        QuerySpec top = planner.plan("Spending by merchant in November, top 5", CTX);
        assertEquals(QuerySpec.GroupBy.MERCHANT, top.groupBy());
        assertEquals(5, top.limit());
    }

    @Test
    void incomeIsCredit() {
        QuerySpec s = planner.plan("Total income in the second half of the year", CTX);
        assertEquals(TransactionType.CREDIT, s.type());
        assertEquals(LocalDate.of(2025, 7, 1), s.from());
        assertEquals(LocalDate.of(2025, 12, 31), s.to());
    }
}
