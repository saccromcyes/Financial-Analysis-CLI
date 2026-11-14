package com.example.finance.web;

import com.example.finance.Fixtures;
import com.example.finance.model.Transaction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.example.finance.Fixtures.debit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardServiceTest {
    private final DashboardService dashboard = new DashboardService();

    private static List<String> reasons(List<Map<String, Object>> flags) {
        return flags.stream().map(f -> f.get("date") + " " + f.get("reason")).toList();
    }

    @Test
    void totalsAndSavingsRate() {
        @SuppressWarnings("unchecked")
        Map<String, Object> kpis = (Map<String, Object>) dashboard.summary(Fixtures.SMALL).get("kpis");
        assertEquals(3000.0, (double) kpis.get("income"), 0.001);
        assertEquals(1490.75, (double) kpis.get("spent"), 0.001);
        assertEquals(1509.25, (double) kpis.get("net"), 0.001);
        assertEquals(0.5, (double) kpis.get("savings_rate"), 0.001);
    }

    /** Three ordinary months: rent and groceries every month. */
    private static List<Transaction> quarter() {
        List<Transaction> data = new ArrayList<>();
        for (int m = 1; m <= 3; m++) {
            data.add(debit(String.format("2025-%02d-01", m), "Rent", "Housing", 1200.00, "Landlord LLC"));
            data.add(debit(String.format("2025-%02d-06", m), "Weekly shop", "Groceries", 80.00 + m, "Fresh Market"));
            data.add(debit(String.format("2025-%02d-20", m), "Weekly shop", "Groceries", 75.00 - m, "Fresh Market"));
        }
        return data;
    }

    @Test
    void flagsAOneOffLargePurchase() {
        List<Transaction> data = quarter();
        data.add(debit("2025-03-20", "New laptop", "Electronics", 1899.00, "TechHub"));
        List<Map<String, Object>> flags = dashboard.anomalies(data);
        assertEquals(1, flags.size(), reasons(flags).toString());
        assertTrue(String.valueOf(flags.get(0).get("reason")).startsWith("A one-off electronics purchase"));
    }

    @Test
    void aOneMonthStatementDoesNotCallTheRentAOneOff() {
        List<Transaction> oneMonth = quarter().stream().filter(t -> t.date().getMonthValue() == 1).toList();
        assertEquals(List.of(), dashboard.anomalies(oneMonth));
    }

    @Test
    void flagsAnOutlierWithinItsCategory() {
        List<Transaction> data = new ArrayList<>();
        for (int day = 1; day <= 12; day++) {
            data.add(debit(String.format("2025-01-%02d", day), "Weekly shop", "Groceries", 48 + day % 5, "Fresh Market"));
        }
        data.add(debit("2025-01-20", "Party supplies", "Groceries", 640.00, "Fresh Market"));
        List<Map<String, Object>> flags = dashboard.anomalies(data);
        assertEquals(1, flags.size(), reasons(flags).toString());
        assertEquals(640.0, (double) flags.get(0).get("amount"), 0.001);
        assertTrue(String.valueOf(flags.get(0).get("reason")).contains("usual groceries amount"));
    }

    @Test
    void flagsADoubleSubscriptionCharge() {
        List<Transaction> data = List.of(
                debit("2025-08-01", "StreamFlix", "Subscriptions", 15.99, "StreamFlix"),
                debit("2025-08-01", "StreamFlix", "Subscriptions", 15.99, "StreamFlix"),
                debit("2025-09-01", "StreamFlix", "Subscriptions", 15.99, "StreamFlix"),
                debit("2025-09-04", "StreamFlix", "Subscriptions", 15.99, "StreamFlix"));
        List<String> found = reasons(dashboard.anomalies(data));
        assertEquals(List.of("2025-08-01 Same subscription charged twice on the same day",
                "2025-09-04 Same subscription charged twice within 3 days"), found);
    }

    @Test
    void ordinarySpendingIsNotFlagged() {
        assertEquals(List.of(), dashboard.anomalies(quarter()));
    }

    @Test
    void recurringNeedsTheSameAmountInThreeMonths() {
        List<Transaction> data = List.of(
                debit("2025-01-03", "Gym", "Health", 39.00, "FitLab Gym"),
                debit("2025-02-03", "Gym", "Health", 39.00, "FitLab Gym"),
                debit("2025-03-03", "Gym", "Health", 39.00, "FitLab Gym"),
                debit("2025-01-09", "Phone", "Bills", 20.00, "TelcoOne"),
                debit("2025-02-09", "Phone", "Bills", 20.00, "TelcoOne"),
                debit("2025-03-09", "Phone", "Bills", 25.00, "TelcoOne"));
        List<Map<String, Object>> recurring = dashboard.recurring(data);
        assertEquals(1, recurring.size());
        assertEquals("FitLab Gym", recurring.get(0).get("merchant"));
        assertEquals(468.0, (double) recurring.get(0).get("yearly_cost"), 0.001);
    }

    @Test
    void forecastUsesTheMedianSoOneBigMonthDoesNotSkewIt() {
        List<Transaction> data = new ArrayList<>();
        double[] monthly = {1000, 1100, 900, 1050, 5000, 950};
        for (int m = 0; m < monthly.length; m++) {
            data.add(debit(String.format("2025-%02d-10", m + 1), "Spend", "Shopping", monthly[m], "Store"));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> forecast = (List<Map<String, Object>>) dashboard.summary(data).get("forecast");
        assertEquals(3, forecast.size());
        assertEquals("2025-07", String.valueOf(forecast.get(0).get("month")));
        assertEquals(1025.0, (double) forecast.get(0).get("spent"), 0.001);
    }

    @Test
    void findsThePlantedProblemsInTheSampleYear() {
        List<String> found = reasons(dashboard.anomalies(Fixtures.sample()));
        assertTrue(found.stream().anyMatch(r -> r.startsWith("2025-04-22 A one-off electronics")), found.toString());
        assertTrue(found.stream().anyMatch(r -> r.startsWith("2025-08-07 Same subscription charged twice")), found.toString());
    }
}
