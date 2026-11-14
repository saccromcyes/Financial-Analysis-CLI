package com.example.finance.ask;

import com.example.finance.model.Transaction;
import com.example.finance.model.TransactionType;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Turns a query result into one plain sentence. Templated, so it can only state computed numbers. */
public final class AnswerWriter {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private final String symbol;

    public AnswerWriter(String symbol) {
        this.symbol = symbol;
    }

    public String write(QueryEngine.Result r) {
        QuerySpec s = r.spec();
        String scope = scope(s);
        if (r.matched() == 0) {
            return "No transactions match" + (scope.isEmpty() ? "." : ": " + scope.trim() + ".");
        }
        String n = r.matched() + " transaction" + (r.matched() == 1 ? "" : "s");
        if (s.metric() == QuerySpec.Metric.LIST) {
            return "Found " + n + scope + ". Showing the " + r.rows().size() + " " + (s.descending() ? "largest" : "smallest") + ".";
        }
        if (s.groupBy() != QuerySpec.GroupBy.NONE) {
            String by = s.groupBy().name().toLowerCase(Locale.ROOT);
            String what = switch (s.metric()) {
                case COUNT -> "Number of transactions";
                case AVERAGE -> "Average amount";
                case MAX -> "Largest amount";
                case MIN -> "Smallest amount";
                default -> verb(s.type()).substring(0, 1).toUpperCase(Locale.ROOT) + verb(s.type()).substring(1);
            };
            boolean counting = s.metric() == QuerySpec.Metric.COUNT;
            java.util.function.Function<QueryEngine.Group, String> fmt = g -> counting ? String.valueOf((long) g.value()) : money(g.value());
            if (r.groups().size() == 1 && s.limit() == 1) {
                QueryEngine.Group top = r.groups().get(0);
                return top.key() + ", with " + money(top.value()) + scope + " (" + top.count() + " transaction" + (top.count() == 1 ? "" : "s") + ").";
            }
            if (s.groupBy() == QuerySpec.GroupBy.MONTH && r.groups().size() > 2) {
                // Months: say what is typical and which month stands out, rather than reading the table aloud.
                List<Double> values = r.groups().stream().map(QueryEngine.Group::value).sorted().toList();
                double median = values.get(values.size() / 2);
                QueryEngine.Group high = r.groups().stream().max(java.util.Comparator.comparingDouble(QueryEngine.Group::value)).orElseThrow();
                String typical = counting ? String.valueOf((long) median) : money(median);
                String noun = switch (s.metric()) {
                    case SUM -> s.type() == TransactionType.CREDIT ? "Income" : s.type() == TransactionType.DEBIT ? "Spending" : "Money moved";
                    default -> what;
                };
                return noun + scope + " is usually " + typical + " a month; the highest was " + fmt.apply(high) + " in "
                        + java.time.YearMonth.parse(high.key()).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
                        + " (" + r.groups().size() + " months).";
            }
            List<QueryEngine.Group> top = r.groups().stream().limit(3).toList();
            String lead = top.get(0).key() + " " + fmt.apply(top.get(0));
            String rest = top.stream().skip(1).map(g -> g.key() + " " + fmt.apply(g)).collect(Collectors.joining(" and "));
            return what + " by " + by + scope + ": " + lead + (rest.isEmpty() ? "" : ", then " + rest)
                    + (r.groups().size() > 3 ? " (" + r.groups().size() + " in total)." : ".");
        }
        return switch (s.metric()) {
            case COUNT -> "Found " + n + scope + ".";
            case AVERAGE -> "The average is " + money(r.value()) + " across " + n + scope + ".";
            case MAX, MIN -> {
                Transaction t = r.rows().get(0);
                yield "The " + (s.metric() == QuerySpec.Metric.MAX ? "largest" : "smallest") + " was " + money(t.amount())
                        + ": " + t.description() + " at " + t.destination() + " on " + DAY.format(t.date()) + ".";
            }
            default -> "You " + verb(s.type()) + " " + money(r.value()) + scope + " (" + n + ").";
        };
    }

    private String verb(TransactionType type) {
        if (type == TransactionType.CREDIT) {
            return "received";
        }
        return type == TransactionType.DEBIT ? "spent" : "moved";
    }

    private String scope(QuerySpec s) {
        List<String> parts = new ArrayList<>();
        if (!s.categories().isEmpty()) {
            parts.add("on " + String.join(" and ", s.categories()));
        }
        if (s.text() != null) {
            parts.add("on items matching \"" + s.text() + "\"");
        }
        if (s.merchant() != null) {
            parts.add((s.type() == TransactionType.CREDIT ? "from " : "at ") + s.merchant());
        }
        if (s.minAmount() != null) {
            parts.add("over " + money(s.minAmount()));
        }
        if (s.maxAmount() != null) {
            parts.add("under " + money(s.maxAmount()));
        }
        parts.add(period(s.from(), s.to()));
        return parts.stream().filter(p -> !p.isEmpty()).map(p -> " " + p).collect(Collectors.joining());
    }

    private static String period(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return "";
        }
        if (from != null && to != null && from.getDayOfMonth() == 1 && to.equals(from.withDayOfMonth(from.lengthOfMonth()))) {
            return "in " + from.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH));
        }
        if (from != null && to != null) {
            return "between " + DAY.format(from) + " and " + DAY.format(to);
        }
        return from != null ? "since " + DAY.format(from) : "until " + DAY.format(to);
    }

    public String money(double value) {
        return symbol + String.format(Locale.US, "%,.2f", value);
    }
}
