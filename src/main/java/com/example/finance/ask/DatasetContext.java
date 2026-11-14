package com.example.finance.ask;

import com.example.finance.model.Transaction;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/** What a planner needs to know about a dataset: its vocabulary and date range. */
public record DatasetContext(Set<String> categories, List<String> merchants, Set<String> keywords, LocalDate first, LocalDate last) {

    public static DatasetContext of(List<Transaction> transactions) {
        Set<String> categories = transactions.stream().map(Transaction::category)
                .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
        // Most frequent merchants first, so a prompt can include the useful ones.
        List<String> merchants = transactions.stream()
                .collect(Collectors.groupingBy(Transaction::destination, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).toList();
        // Words from descriptions, minus boilerplate: "Payment - Coffee" gives "coffee".
        Set<String> keywords = transactions.stream()
                .map(t -> t.description().replaceAll("(?i)^(payment|subscription)\\s*-\\s*", "").toLowerCase(Locale.ROOT))
                .flatMap(d -> List.of(d.split("[^a-z0-9]+")).stream())
                .filter(w -> w.length() > 2)
                .collect(Collectors.toCollection(TreeSet::new));
        Function<Transaction, LocalDate> date = Transaction::date;
        LocalDate first = transactions.stream().map(date).min(Comparator.naturalOrder()).orElse(LocalDate.now());
        LocalDate last = transactions.stream().map(date).max(Comparator.naturalOrder()).orElse(LocalDate.now());
        return new DatasetContext(categories, merchants, keywords, first, last);
    }
}
