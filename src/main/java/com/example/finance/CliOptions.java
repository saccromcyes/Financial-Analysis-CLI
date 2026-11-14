package com.example.finance;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Command-line options. {@code --serve} starts the web dashboard instead of printing a report;
 * {@code --ask "question"} answers one question and exits.
 */
public record CliOptions(Path csvPath, double threshold, int previewLimit, Path exportDir, String currency,
                         boolean serve, int port, String question) {
    private static final double DEFAULT_THRESHOLD = 500.0;
    private static final int DEFAULT_LIMIT = 5;
    private static final String DEFAULT_CURRENCY = "USD";

    public static CliOptions parse(String[] args) {
        Map<String, String> parsed = new HashMap<>();
        boolean serve = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--serve")) {
                serve = true;
            } else if (args[i].startsWith("--") && i + 1 < args.length) {
                parsed.put(args[i], args[i + 1]);
                i++;
            }
        }
        Path csvPath = parsed.containsKey("--csv") ? Path.of(parsed.get("--csv")) : null;
        double threshold = parsed.containsKey("--threshold") ? Double.parseDouble(parsed.get("--threshold")) : DEFAULT_THRESHOLD;
        int limit = parsed.containsKey("--limit") ? Integer.parseInt(parsed.get("--limit")) : DEFAULT_LIMIT;
        Path exportDir = parsed.containsKey("--export-dir") ? Path.of(parsed.get("--export-dir")) : Path.of("out");
        String currency = parsed.getOrDefault("--currency", DEFAULT_CURRENCY);
        String envPort = System.getenv("PORT");
        int port = Integer.parseInt(parsed.getOrDefault("--port", envPort == null || envPort.isBlank() ? "7860" : envPort));
        return new CliOptions(csvPath, threshold, limit, exportDir, currency, serve, port, parsed.get("--ask"));
    }

    public String currencySymbol() {
        return switch (currency.toUpperCase()) {
            case "USD" -> "$";
            case "EUR" -> "€";
            case "GBP" -> "£";
            case "INR" -> "₹";
            default -> currency.toUpperCase() + " ";
        };
    }
}
