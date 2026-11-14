package com.example.finance.web;

import com.example.finance.ask.AskService;
import com.example.finance.model.Transaction;
import com.example.finance.model.TransactionType;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Everything the dashboard shows, computed from the transactions: monthly cash flow, spending by
 * category, unusual transactions, recurring charges and a short forecast.
 */
public final class DashboardService {

    public Map<String, Object> summary(List<Transaction> transactions) {
        Map<String, Object> out = new LinkedHashMap<>();
        double income = sum(transactions, TransactionType.CREDIT);
        double spent = sum(transactions, TransactionType.DEBIT);
        Map<YearMonth, double[]> months = new TreeMap<>();
        for (Transaction t : transactions) {
            double[] m = months.computeIfAbsent(YearMonth.from(t.date()), k -> new double[2]);
            m[t.type() == TransactionType.CREDIT ? 0 : 1] += t.amount();
        }
        out.put("kpis", Map.of(
                "income", round(income),
                "spent", round(spent),
                "net", round(income - spent),
                "savings_rate", income == 0 ? 0.0 : round((income - spent) / income),
                "transactions", transactions.size(),
                "from", transactions.stream().map(Transaction::date).min(Comparator.naturalOrder()).orElse(null),
                "to", transactions.stream().map(Transaction::date).max(Comparator.naturalOrder()).orElse(null)));
        out.put("months", months.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", e.getKey());
            m.put("income", round(e.getValue()[0]));
            m.put("spent", round(e.getValue()[1]));
            return m;
        }).toList());
        out.put("categories", transactions.stream().filter(t -> t.type() == TransactionType.DEBIT)
                .collect(Collectors.groupingBy(Transaction::category, Collectors.summingDouble(Transaction::amount)))
                .entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> Map.<String, Object>of("category", e.getKey(), "spent", round(e.getValue()))).toList());
        out.put("anomalies", anomalies(transactions));
        out.put("recurring", recurring(transactions));
        out.put("forecast", forecast(months));
        return out;
    }

    /**
     * Unusual spending: a large purchase in a category seen only once in three or more months of data,
     * a debit more than 3 standard deviations above its own category's mean, or the same subscription
     * charged twice within 5 days (a likely double charge).
     */
    List<Map<String, Object>> anomalies(List<Transaction> transactions) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, DoubleSummaryStatistics> stats = new LinkedHashMap<>();
        Map<String, List<Transaction>> byCategory = transactions.stream().filter(t -> t.type() == TransactionType.DEBIT)
                .collect(Collectors.groupingBy(Transaction::category));
        byCategory.forEach((c, list) -> stats.put(c, list.stream().mapToDouble(Transaction::amount).summaryStatistics()));
        long spanMonths = transactions.stream().map(t -> YearMonth.from(t.date())).distinct().count();
        for (Map.Entry<String, List<Transaction>> e : byCategory.entrySet()) {
            List<Transaction> list = e.getValue();
            double mean = stats.get(e.getKey()).getAverage();
            double sd = Math.sqrt(list.stream().mapToDouble(t -> Math.pow(t.amount() - mean, 2)).average().orElse(0));
            // A category seen once is only "one-off" if the data is long enough for monthly bills to have repeated;
            // in a one-month statement the rent is also seen once.
            boolean singleton = list.size() == 1 && spanMonths >= 3;
            for (Transaction t : list) {
                if (singleton && t.amount() >= 300) {
                    out.add(flag(t, "A one-off " + e.getKey().toLowerCase() + " purchase, much larger than typical spending"));
                } else if (sd > 0 && list.size() >= 5 && (t.amount() - mean) / sd > 3) {
                    out.add(flag(t, String.format("%.1f× the usual %s amount", t.amount() / mean, e.getKey().toLowerCase())));
                }
            }
        }
        List<Transaction> debits = transactions.stream().filter(t -> t.type() == TransactionType.DEBIT)
                .sorted(Comparator.comparing(Transaction::date)).toList();
        for (int i = 0; i < debits.size(); i++) {
            for (int j = i + 1; j < debits.size() && !debits.get(j).date().isAfter(debits.get(i).date().plusDays(5)); j++) {
                Transaction a = debits.get(i);
                Transaction b = debits.get(j);
                if (a.destination().equals(b.destination()) && a.amount() == b.amount() && a.category().equals("Subscriptions")) {
                    long gap = b.date().toEpochDay() - a.date().toEpochDay();
                    out.add(flag(b, gap == 0 ? "Same subscription charged twice on the same day"
                            : "Same subscription charged twice within " + gap + " day" + (gap == 1 ? "" : "s")));
                }
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("date"))));
        return out;
    }

    /** Merchants charged the same amount in at least 3 different months: subscriptions and bills. */
    List<Map<String, Object>> recurring(List<Transaction> transactions) {
        Map<String, List<Transaction>> bySignature = transactions.stream().filter(t -> t.type() == TransactionType.DEBIT)
                .collect(Collectors.groupingBy(t -> t.destination() + "|" + t.amount()));
        List<Map<String, Object>> out = new ArrayList<>();
        bySignature.forEach((sig, list) -> {
            long months = list.stream().map(t -> YearMonth.from(t.date())).distinct().count();
            if (months >= 3) {
                Transaction t = list.get(0);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("merchant", t.destination());
                m.put("amount", t.amount());
                m.put("category", t.category());
                m.put("months", months);
                m.put("yearly_cost", round(t.amount() * 12));
                out.add(m);
            }
        });
        out.sort(Comparator.comparingDouble((Map<String, Object> m) -> (double) m.get("yearly_cost")).reversed());
        return out;
    }

    /** Next three months of spending: the median of the last six months, so one big purchase doesn't skew it. */
    List<Map<String, Object>> forecast(Map<YearMonth, double[]> months) {
        List<Map.Entry<YearMonth, double[]>> history = new ArrayList<>(months.entrySet());
        if (history.size() < 3) {
            return List.of();
        }
        List<Double> recent = history.subList(Math.max(0, history.size() - 6), history.size()).stream()
                .map(e -> e.getValue()[1]).sorted().toList();
        double median = recent.size() % 2 == 1 ? recent.get(recent.size() / 2)
                : (recent.get(recent.size() / 2 - 1) + recent.get(recent.size() / 2)) / 2;
        YearMonth last = history.get(history.size() - 1).getKey();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            out.add(Map.of("month", last.plusMonths(i), "spent", round(median)));
        }
        return out;
    }

    private static Map<String, Object> flag(Transaction t, String reason) {
        Map<String, Object> m = AskService.row(t);
        m.put("reason", reason);
        return m;
    }

    private static double sum(List<Transaction> transactions, TransactionType type) {
        return transactions.stream().filter(t -> t.type() == type).mapToDouble(Transaction::amount).sum();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
