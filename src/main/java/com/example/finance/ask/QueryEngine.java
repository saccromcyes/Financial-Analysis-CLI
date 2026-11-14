package com.example.finance.ask;

import com.example.finance.model.Transaction;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Executes a {@link QuerySpec} over transactions. Deterministic: same spec, same numbers. */
public final class QueryEngine {

    public record Group(String key, double value, long count) {
    }

    public record Result(QuerySpec spec, double value, long matched, List<Group> groups, List<Transaction> rows) {
    }

    public Result execute(QuerySpec spec, List<Transaction> transactions) {
        List<Transaction> matched = transactions.stream().filter(t -> matches(spec, t)).toList();

        if (spec.metric() == QuerySpec.Metric.LIST) {
            Comparator<Transaction> byAmount = Comparator.comparingDouble(Transaction::amount);
            List<Transaction> rows = matched.stream()
                    .sorted(spec.descending() ? byAmount.reversed() : byAmount)
                    .limit(spec.limit())
                    .toList();
            return new Result(spec, aggregate(spec.metric(), matched), matched.size(), List.of(), rows);
        }

        if (spec.groupBy() == QuerySpec.GroupBy.NONE) {
            // For max/min, also return the transaction itself so the answer can say what it was.
            List<Transaction> extreme = switch (spec.metric()) {
                case MAX -> matched.stream().max(Comparator.comparingDouble(Transaction::amount)).stream().toList();
                case MIN -> matched.stream().min(Comparator.comparingDouble(Transaction::amount)).stream().toList();
                default -> List.of();
            };
            return new Result(spec, aggregate(spec.metric(), matched), matched.size(), List.of(), extreme);
        }

        Function<Transaction, String> key = switch (spec.groupBy()) {
            case CATEGORY -> Transaction::category;
            case MERCHANT -> Transaction::destination;
            case MONTH -> t -> YearMonth.from(t.date()).toString();
            case NONE -> t -> "all";
        };
        Map<String, List<Transaction>> buckets = matched.stream()
                .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
        List<Group> groups = new ArrayList<>();
        buckets.forEach((k, list) -> groups.add(new Group(k, aggregate(spec.metric(), list), list.size())));
        Comparator<Group> order = spec.groupBy() == QuerySpec.GroupBy.MONTH
                ? Comparator.comparing(Group::key) // months read best in calendar order
                : (spec.descending() ? Comparator.comparingDouble(Group::value).reversed() : Comparator.comparingDouble(Group::value));
        List<Group> sorted = groups.stream().sorted(order).limit(spec.limit()).toList();
        return new Result(spec, aggregate(spec.metric(), matched), matched.size(), sorted, List.of());
    }

    static boolean matches(QuerySpec s, Transaction t) {
        if (s.type() != null && t.type() != s.type()) {
            return false;
        }
        if (!s.categories().isEmpty() && s.categories().stream().noneMatch(c -> c.equalsIgnoreCase(t.category()))) {
            return false;
        }
        if (s.merchant() != null && !contains(t.destination(), s.merchant())) {
            return false;
        }
        if (s.text() != null && !contains(t.description(), s.text()) && !contains(t.destination(), s.text())) {
            return false;
        }
        if (s.from() != null && t.date().isBefore(s.from())) {
            return false;
        }
        if (s.to() != null && t.date().isAfter(s.to())) {
            return false;
        }
        if (s.minAmount() != null && t.amount() < s.minAmount()) {
            return false;
        }
        return s.maxAmount() == null || t.amount() <= s.maxAmount();
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static double aggregate(QuerySpec.Metric metric, List<Transaction> list) {
        var stats = list.stream().mapToDouble(Transaction::amount).summaryStatistics();
        double value = switch (metric) {
            case SUM, LIST -> stats.getSum();
            case COUNT -> list.size();
            case AVERAGE -> list.isEmpty() ? 0.0 : stats.getAverage();
            case MAX -> list.isEmpty() ? 0.0 : stats.getMax();
            case MIN -> list.isEmpty() ? 0.0 : stats.getMin();
        };
        return Math.round(value * 100.0) / 100.0;
    }
}
