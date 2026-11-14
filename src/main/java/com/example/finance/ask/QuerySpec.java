package com.example.finance.ask;

import com.example.finance.model.TransactionType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A question about transactions, expressed as a small, validated query. The language model only
 * ever produces one of these; the numbers in an answer always come from {@link QueryEngine}.
 */
public record QuerySpec(
        Metric metric,
        TransactionType type,
        List<String> categories,
        String merchant,
        String text,
        LocalDate from,
        LocalDate to,
        Double minAmount,
        Double maxAmount,
        GroupBy groupBy,
        boolean descending,
        int limit
) {
    public enum Metric { SUM, COUNT, AVERAGE, MAX, MIN, LIST }

    public enum GroupBy { NONE, CATEGORY, MONTH, MERCHANT }

    public static final int MAX_LIMIT = 50;
    private static final Set<String> KNOWN_FIELDS = Set.of(
            "metric", "type", "categories", "merchant", "text", "from", "to",
            "min_amount", "max_amount", "group_by", "sort", "limit");

    public QuerySpec {
        categories = categories == null ? List.of() : List.copyOf(categories);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
    }

    /**
     * Build a spec from untrusted JSON (for example, model output). Unknown fields and invalid
     * values are rejected rather than guessed, and categories are matched to the dataset's own.
     */
    public static QuerySpec fromMap(Map<String, Object> raw, Set<String> knownCategories) {
        for (String key : raw.keySet()) {
            if (!KNOWN_FIELDS.contains(key)) {
                throw new IllegalArgumentException("Unknown field: " + key);
            }
        }
        Metric metric = enumValue(Metric.class, raw.get("metric"), Metric.SUM);
        TransactionType type = raw.get("type") == null ? null : enumValue(TransactionType.class, raw.get("type"), null);
        List<String> categories = new ArrayList<>();
        if (raw.get("categories") instanceof List<?> list) {
            for (Object item : list) {
                String wanted = String.valueOf(item).trim();
                String match = knownCategories.stream().filter(c -> c.equalsIgnoreCase(wanted)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown category: " + wanted));
                categories.add(match);
            }
        } else if (raw.get("categories") != null) {
            throw new IllegalArgumentException("categories must be a list");
        }
        String sort = raw.get("sort") == null ? "desc" : String.valueOf(raw.get("sort")).toLowerCase(Locale.ROOT);
        if (!sort.equals("desc") && !sort.equals("asc")) {
            throw new IllegalArgumentException("sort must be asc or desc");
        }
        int limit = raw.get("limit") instanceof Number n ? n.intValue() : 10;
        return new QuerySpec(
                metric,
                type,
                categories,
                blankToNull(raw.get("merchant")),
                blankToNull(raw.get("text")),
                date(raw.get("from")),
                date(raw.get("to")),
                number(raw.get("min_amount")),
                number(raw.get("max_amount")),
                enumValue(GroupBy.class, raw.get("group_by"), GroupBy.NONE),
                sort.equals("desc"),
                Math.max(1, Math.min(MAX_LIMIT, limit)));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", metric.name().toLowerCase(Locale.ROOT));
        m.put("type", type == null ? null : type.name());
        m.put("categories", categories);
        m.put("merchant", merchant);
        m.put("text", text);
        m.put("from", from);
        m.put("to", to);
        m.put("min_amount", minAmount);
        m.put("max_amount", maxAmount);
        m.put("group_by", groupBy.name().toLowerCase(Locale.ROOT));
        m.put("sort", descending ? "desc" : "asc");
        m.put("limit", limit);
        return m;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid " + type.getSimpleName() + ": " + value);
        }
    }

    private static String blankToNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static LocalDate date(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(value).trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Dates must be YYYY-MM-DD: " + value);
        }
    }

    private static Double number(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            // Models sometimes quote numbers ("500"); a currency sign or a word is still rejected.
            double d = Double.parseDouble(String.valueOf(value).trim());
            if (Double.isFinite(d)) {
                return d;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new IllegalArgumentException("Expected a number: " + value);
    }
}
