package com.example.finance;

import com.example.finance.io.FinancialCsvReader;
import com.example.finance.model.Transaction;
import com.example.finance.model.TransactionType;

import java.time.LocalDate;
import java.util.List;

/** Small, hand-checkable transaction sets shared by the tests. */
public final class Fixtures {
    private Fixtures() {
    }

    public static Transaction tx(String date, String description, String category, TransactionType type, double amount, String merchant) {
        return new Transaction(LocalDate.parse(date), description, category, type, amount, 0.0, merchant);
    }

    public static Transaction debit(String date, String description, String category, double amount, String merchant) {
        return tx(date, description, category, TransactionType.DEBIT, amount, merchant);
    }

    /** Six transactions across three months; totals are easy to check by hand. */
    public static final List<Transaction> SMALL = List.of(
            debit("2025-01-05", "Weekly shop", "Groceries", 80.00, "Fresh Market"),
            debit("2025-01-20", "Weekly shop", "Groceries", 70.00, "Fresh Market"),
            debit("2025-02-03", "Dinner out", "Food & Dining", 45.50, "Bistro Nine"),
            tx("2025-02-15", "Monthly Salary", "Income", TransactionType.CREDIT, 3000.00, "Employer Inc"),
            debit("2025-03-01", "Rent", "Housing", 1200.00, "Landlord LLC"),
            debit("2025-03-09", "Weekly shop", "Groceries", 95.25, "Fresh Market"));

    /** The bundled sample year used by the dashboard and the evaluation. */
    public static List<Transaction> sample() {
        return FinancialCsvReader.readTransactions(null).transactions();
    }
}
