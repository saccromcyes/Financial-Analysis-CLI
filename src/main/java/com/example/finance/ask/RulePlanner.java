package com.example.finance.ask;

import com.example.finance.model.TransactionType;

import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyword and pattern rules. Used when no language model is configured or the model fails; it
 * handles common phrasings well and is the baseline the LLM planner is measured against.
 */
public final class RulePlanner implements QueryPlanner {
    private static final Map<String, String> CATEGORY_SYNONYMS = Map.ofEntries(
            Map.entry("grocery", "Groceries"), Map.entry("groceries", "Groceries"),
            Map.entry("rent", "Housing"), Map.entry("housing", "Housing"),
            Map.entry("eating out", "Food & Dining"), Map.entry("dining", "Food & Dining"), Map.entry("food", "Food & Dining"),
            Map.entry("transport", "Transport"), Map.entry("transit", "Transport"), Map.entry("commute", "Transport"),
            Map.entry("subscription", "Subscriptions"), Map.entry("utilities", "Utilities"), Map.entry("utility", "Utilities"),
            Map.entry("health", "Health"), Map.entry("fitness", "Health"), Map.entry("shopping", "Shopping"),
            Map.entry("electronics", "Electronics"), Map.entry("travel", "Travel"), Map.entry("income", "Income"));
    /** Words that describe how to group or sort, never what to search for ("monthly" is not an item). */
    private static final java.util.Set<String> GROUPING_WORDS = java.util.Set.of(
            "monthly", "month", "months", "category", "categories", "merchant", "merchants", "total", "average", "spending");
    private static final Pattern TOP_N = Pattern.compile("\\btop\\s+(\\d{1,2})\\b");
    private static final Pattern YEAR = Pattern.compile("\\b(20\\d{2})\\b");
    private static final Pattern MIN_AMOUNT = Pattern.compile("(?:over|above|more than|greater than|at least)\\s*[$€£₹]?\\s*([\\d,]+(?:\\.\\d+)?)");
    private static final Pattern MAX_AMOUNT = Pattern.compile("(?:under|below|less than|at most)\\s*[$€£₹]?\\s*([\\d,]+(?:\\.\\d+)?)");

    @Override
    public String name() {
        return "rules";
    }

    @Override
    public QuerySpec plan(String question, DatasetContext ctx) {
        String q = " " + question.toLowerCase(Locale.ROOT).replaceAll("[?!.,;:()\"]", " ").replaceAll("\\s+", " ") + " ";

        QuerySpec.GroupBy group = QuerySpec.GroupBy.NONE;
        if (q.matches(".*\\b(by|per|each|which|what) (category|categories)\\b.*")) {
            group = QuerySpec.GroupBy.CATEGORY;
        } else if (q.matches(".*\\b(per month|each month|by month|monthly|month by month|trend)\\b.*")) {
            group = QuerySpec.GroupBy.MONTH;
        } else if (q.matches(".*\\b(by|per|each|which) (merchant|shop|store|place)s?\\b.*")) {
            group = QuerySpec.GroupBy.MERCHANT;
        }

        QuerySpec.Metric metric = QuerySpec.Metric.SUM;
        if (q.matches(".*\\b(how many|number of|count)\\b.*")) {
            metric = QuerySpec.Metric.COUNT;
        } else if (q.matches(".*\\b(average|avg|typical|mean)\\b.*")) {
            metric = QuerySpec.Metric.AVERAGE;
        } else if (q.matches(".*\\b(list|show me|which transactions|what transactions)\\b.*")) {
            metric = QuerySpec.Metric.LIST;
        } else if (group == QuerySpec.GroupBy.NONE && q.matches(".*\\b(biggest|largest|most expensive|highest|max)\\b.*")) {
            metric = QuerySpec.Metric.MAX;
        } else if (group == QuerySpec.GroupBy.NONE && q.matches(".*\\b(smallest|cheapest|lowest|min)\\b.*")) {
            metric = QuerySpec.Metric.MIN;
        }

        TransactionType type = null;
        if (q.matches(".*\\b(income|earn|earned|received|salary|paid me|freelance)\\b.*")) {
            type = TransactionType.CREDIT;
        } else if (q.matches(".*\\b(spend|spent|spending|expense|expenses|cost|costs|pay|paid|bill|bills|buy|bought|purchase)\\b.*")) {
            type = TransactionType.DEBIT;
        }

        List<String> categories = new ArrayList<>();
        for (String category : ctx.categories()) {
            if (q.contains(" " + category.toLowerCase(Locale.ROOT) + " ") && !categories.contains(category)) {
                categories.add(category);
            }
        }
        for (Map.Entry<String, String> syn : CATEGORY_SYNONYMS.entrySet()) {
            if (q.contains(" " + syn.getKey() + " ") && ctx.categories().contains(syn.getValue()) && !categories.contains(syn.getValue())) {
                categories.add(syn.getValue());
            }
        }
        if (categories.contains("Income") && type == TransactionType.CREDIT) {
            categories.remove("Income"); // "income" already means credits
        }

        String merchant = ctx.merchants().stream()
                .filter(m -> q.contains(" " + m.toLowerCase(Locale.ROOT) + " ") || q.contains(" " + m.toLowerCase(Locale.ROOT) + "'"))
                .findFirst().orElse(null);
        String text = null;
        if (merchant == null) {
            for (String word : q.trim().split("[^a-z0-9]+")) {
                String singular = word.endsWith("s") && word.length() > 4 ? word.substring(0, word.length() - 1) : word;
                if (ctx.keywords().contains(singular) && CATEGORY_SYNONYMS.get(singular) == null && !GROUPING_WORDS.contains(singular)
                        && categories.stream().noneMatch(c -> c.equalsIgnoreCase(singular))) {
                    text = singular;
                    break;
                }
            }
        }
        if (text != null && categories.size() == 1 && !text.isBlank()) {
            categories.clear(); // a specific item ("coffee") is narrower than its category
        }

        LocalDate[] range = dateRange(q, ctx);
        Double min = amount(MIN_AMOUNT, q);
        Double max = amount(MAX_AMOUNT, q);

        int limit = 10;
        Matcher top = TOP_N.matcher(q);
        if (top.find()) {
            limit = Math.max(1, Math.min(QuerySpec.MAX_LIMIT, Integer.parseInt(top.group(1))));
        } else if (group == QuerySpec.GroupBy.MONTH) {
            limit = QuerySpec.MAX_LIMIT; // "per month" means every month, not the first ten
        } else if (group != QuerySpec.GroupBy.NONE && q.matches(".*\\b(which|what) (category|merchant|shop|store)\\b.*\\b(most|least)\\b.*")) {
            limit = 1;
        }
        boolean descending = !q.matches(".*\\b(least|smallest|cheapest|lowest)\\b.*");
        if (group == QuerySpec.GroupBy.NONE && metric == QuerySpec.Metric.SUM && type == null && categories.isEmpty()
                && merchant == null && text == null && min == null && max == null) {
            type = TransactionType.DEBIT; // "how much in March?" means spending
        }
        return new QuerySpec(metric, type, categories, merchant, text, range[0], range[1], min, max, group, descending, limit);
    }

    private static LocalDate[] dateRange(String q, DatasetContext ctx) {
        Matcher y = YEAR.matcher(q);
        Integer year = y.find() ? Integer.parseInt(y.group(1)) : null;
        int defaultYear = ctx.last().getYear();
        if (q.contains(" last month ")) {
            YearMonth m = YearMonth.from(ctx.last()).minusMonths(1);
            return new LocalDate[] {m.atDay(1), m.atEndOfMonth()};
        }
        if (q.contains(" this month ")) {
            YearMonth m = YearMonth.from(ctx.last());
            return new LocalDate[] {m.atDay(1), m.atEndOfMonth()};
        }
        for (int quarter = 1; quarter <= 4; quarter++) {
            if (q.contains(" q" + quarter + " ")) {
                int yy = year == null ? defaultYear : year;
                YearMonth start = YearMonth.of(yy, quarter * 3 - 2);
                return new LocalDate[] {start.atDay(1), start.plusMonths(2).atEndOfMonth()};
            }
        }
        if (q.contains(" first half ") || q.contains(" second half ")) {
            int yy = year == null ? defaultYear : year;
            return q.contains(" first half ")
                    ? new LocalDate[] {LocalDate.of(yy, 1, 1), LocalDate.of(yy, 6, 30)}
                    : new LocalDate[] {LocalDate.of(yy, 7, 1), LocalDate.of(yy, 12, 31)};
        }
        for (Month month : Month.values()) {
            String full = month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
            String shortName = month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toLowerCase(Locale.ROOT);
            boolean named = q.contains(" " + full + " ") || (!shortName.equals("may") && q.contains(" " + shortName + " "));
            if (named) {
                int yy = year == null ? defaultYear : year;
                YearMonth m = YearMonth.of(yy, month);
                return new LocalDate[] {m.atDay(1), m.atEndOfMonth()};
            }
        }
        if (year != null) {
            return new LocalDate[] {LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)};
        }
        return new LocalDate[] {null, null};
    }

    private static Double amount(Pattern pattern, String q) {
        Matcher m = pattern.matcher(q);
        return m.find() ? Double.parseDouble(m.group(1).replace(",", "")) : null;
    }
}
