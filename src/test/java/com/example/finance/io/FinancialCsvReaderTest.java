package com.example.finance.io;

import com.example.finance.model.Transaction;
import com.example.finance.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinancialCsvReaderTest {
    private static final String HEADER = "date,description,category,type,amount,balance,destination\n";

    private static List<Transaction> parse(String csv) throws Exception {
        return FinancialCsvReader.parse(new StringReader(csv)).transactions();
    }

    @Test
    void readsRowsAndSkipsBlankLines() throws Exception {
        List<Transaction> rows = parse(HEADER + "2025-01-12,Payment - Coffee,Food & Dining,DEBIT,4.50,2195.30,Coffee Shop\n\n"
                + "2025-01-31,Monthly Salary,Income,credit,5200,,Employer Inc\n");
        assertEquals(2, rows.size());
        assertEquals(LocalDate.of(2025, 1, 12), rows.get(0).date());
        assertEquals(4.50, rows.get(0).amount(), 0.0);
        assertEquals(TransactionType.CREDIT, rows.get(1).type());
        assertEquals(0.0, rows.get(1).balance(), 0.0);
    }

    @Test
    void rejectsTheWrongHeader() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse("when,what,how much\n2025-01-01,x,1\n"));
        assertTrue(ex.getMessage().contains("date,description,category"), ex.getMessage());
    }

    @Test
    void rejectsAnEmptyFile() {
        assertThrows(IllegalArgumentException.class, () -> parse(""));
    }

    @Test
    void namesTheBadLinesWhenTooManyFail() {
        String bad = HEADER + "2025-01-01,a,b,DEBIT,1,0,x\n" + "yesterday,a,b,DEBIT,1,0,x\n" + "2025-01-02,a,b,REFUND,1,0,x\n"
                + "2025-01-03,a,b,DEBIT,-5,0,x\n";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse(bad));
        assertTrue(ex.getMessage().contains("line 3"), ex.getMessage());
    }

    @Test
    void theBundledSampleLoads() {
        List<Transaction> sample = FinancialCsvReader.readTransactions(null).transactions();
        assertTrue(sample.size() > 300, "sample has " + sample.size() + " rows");
    }
}
