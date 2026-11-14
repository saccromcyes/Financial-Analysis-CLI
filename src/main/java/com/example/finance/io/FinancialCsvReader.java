package com.example.finance.io;

import com.example.finance.analysis.FinancialDataFrame;
import com.example.finance.model.Transaction;
import com.example.finance.model.TransactionType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class FinancialCsvReader {
    private FinancialCsvReader() {
    }

    public static FinancialDataFrame readTransactions(Path csvPath) {
        if (csvPath != null && Files.exists(csvPath)) {
            try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
                return parseCsv(reader);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to read CSV from " + csvPath, ex);
            }
        }

        try (InputStream stream = FinancialCsvReader.class.getResourceAsStream(
                "/sample/financial_transactions.csv")) {
            if (stream == null) {
                throw new IllegalStateException("Sample CSV not found in resources.");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                return parseCsv(reader);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read sample CSV.", ex);
        }
    }

    /**
     * Parse CSV text (header: date,description,category,type,amount,balance,destination).
     * Rows that can't be parsed are reported with their line numbers instead of silently dropped.
     */
    public static FinancialDataFrame parse(Reader source) throws IOException {
        return parseCsv(new BufferedReader(source));
    }

    private static FinancialDataFrame parseCsv(BufferedReader reader) throws IOException {
        List<Transaction> transactions = new ArrayList<>();
        String header = reader.readLine();
        if (header == null || header.isBlank()) {
            throw new IllegalArgumentException("The CSV file is empty.");
        }
        if (!header.toLowerCase().replace(" ", "").startsWith("date,description,category,type,amount")) {
            throw new IllegalArgumentException("Expected the header: date,description,category,type,amount,balance,destination");
        }
        List<Integer> badLines = new ArrayList<>();
        String line;
        int lineNumber = 1;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split(",", -1);
            try {
                if (parts.length < 7) {
                    throw new IllegalArgumentException("too few columns");
                }
                double amount = Double.parseDouble(parts[4].trim());
                if (amount < 0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
                    throw new IllegalArgumentException("amount must be a positive number");
                }
                transactions.add(new Transaction(
                        LocalDate.parse(parts[0].trim()),
                        parts[1].trim(),
                        parts[2].trim(),
                        TransactionType.from(parts[3]),
                        amount,
                        parts[5].isBlank() ? 0.0 : Double.parseDouble(parts[5].trim()),
                        parts[6].trim()));
            } catch (RuntimeException ex) {
                badLines.add(lineNumber);
            }
        }
        if (transactions.isEmpty()) {
            throw new IllegalArgumentException("No valid transactions found" + (badLines.isEmpty() ? "." : " (bad lines: " + badLines.subList(0, Math.min(5, badLines.size())) + ")."));
        }
        if (badLines.size() > transactions.size() / 10 + 1) {
            throw new IllegalArgumentException(badLines.size() + " lines couldn't be read, starting with line " + badLines.get(0)
                    + ". Expected YYYY-MM-DD dates, DEBIT/CREDIT types and numeric amounts.");
        }
        return new FinancialDataFrame(transactions);
    }
}
