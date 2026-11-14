package com.example.finance.eval;

import com.example.finance.ask.DatasetContext;
import com.example.finance.ask.LlmPlanner;
import com.example.finance.ask.QueryEngine;
import com.example.finance.ask.QueryPlanner;
import com.example.finance.ask.QuerySpec;
import com.example.finance.ask.RulePlanner;
import com.example.finance.io.FinancialCsvReader;
import com.example.finance.model.Transaction;
import com.example.finance.util.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores question planners against hand-written gold queries on the bundled sample.
 * A question counts as correct when the planner's query returns the same result as the gold
 * query (same total, count, groups or rows), even if the query itself is written differently.
 *
 * <pre>java -cp target/financial-analysis.jar com.example.finance.eval.PlannerEval [--no-write] [--fail-under 0.6]</pre>
 */
public final class PlannerEval {
    private PlannerEval() {
    }

    public static void main(String[] args) throws Exception {
        boolean write = !List.of(args).contains("--no-write");
        double failUnder = 0;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--fail-under")) {
                failUnder = Double.parseDouble(args[i + 1]);
            }
        }
        List<Transaction> data = FinancialCsvReader.readTransactions(null).transactions();
        DatasetContext ctx = DatasetContext.of(data);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> questions = (List<Map<String, Object>>) Json.parse(Files.readString(Path.of("eval", "questions.json")));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("run_at", Instant.now().toString());
        report.put("questions", questions.size());
        Map<String, Object> planners = new LinkedHashMap<>();
        Map<String, Object> rules = score(new RulePlanner(), questions, data, ctx, 0);
        planners.put("rules", rules);
        LlmPlanner llm = LlmPlanner.fromEnv();
        if (llm != null) {
            Map<String, Object> llmScore = score(llm, questions, data, ctx, 2500);
            if ((int) llmScore.get("errors") > questions.size() / 10) {
                System.err.println("FAIL: the language model failed on " + llmScore.get("errors") + " questions; results not published.");
                System.exit(1);
            }
            llmScore.put("model", llm.model());
            planners.put("llm", llmScore);
        }
        report.put("planners", planners);
        planners.forEach((name, s) -> System.out.printf("%-6s accuracy %.0f%%%n", name, 100 * (double) ((Map<?, ?>) s).get("accuracy")));

        if (write) {
            Path out = Path.of("eval", "results");
            Files.createDirectories(out);
            Map<String, Object> previous = Files.exists(out.resolve("latest.json"))
                    ? Json.parseObject(Files.readString(out.resolve("latest.json"))) : Map.of();
            // A run without an API key keeps the last published LLM results rather than dropping them.
            if (llm == null && previous.get("planners") instanceof Map<?, ?> prev && prev.get("llm") != null) {
                planners.put("llm", prev.get("llm"));
            }
            Files.writeString(out.resolve("latest.json"), Json.write(report));
            updateReadme(Path.of("README.md"), planners, questions.size());
        }
        if ((double) rules.get("accuracy") < failUnder) {
            System.err.printf("FAIL: rule planner accuracy %.2f is below %.2f%n", (double) rules.get("accuracy"), failUnder);
            System.exit(1);
        }
    }

    /** Rewrites the table between the eval markers in the README so the published numbers are always the measured ones. */
    static void updateReadme(Path readme, Map<String, Object> planners, int questions) throws java.io.IOException {
        String start = "<!-- eval:start -->";
        String end = "<!-- eval:end -->";
        if (!Files.exists(readme)) {
            return;
        }
        String text = Files.readString(readme);
        int a = text.indexOf(start);
        int b = text.indexOf(end);
        if (a < 0 || b < a) {
            return;
        }
        StringBuilder table = new StringBuilder("\n| Planner | Correct | Accuracy | Avg. time |\n|---|---|---|---|\n");
        planners.forEach((name, raw) -> {
            Map<?, ?> p = (Map<?, ?>) raw;
            String label = name.equals("llm") ? "Language model (`" + p.get("model") + "`)" : "Keyword rules (fallback)";
            table.append(String.format("| %s | %d / %d | **%.0f%%** | %d ms |%n", label, ((Number) p.get("correct")).intValue(), questions,
                    100 * ((Number) p.get("accuracy")).doubleValue(), ((Number) p.get("avg_ms")).longValue()));
        });
        Files.writeString(readme, text.substring(0, a + start.length()) + table + text.substring(b));
    }

    static Map<String, Object> score(QueryPlanner planner, List<Map<String, Object>> questions, List<Transaction> data,
                                     DatasetContext ctx, long pauseMs) throws InterruptedException {
        QueryEngine engine = new QueryEngine();
        int correct = 0;
        int errors = 0;
        long totalMs = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> q : questions) {
            @SuppressWarnings("unchecked")
            QuerySpec gold = QuerySpec.fromMap((Map<String, Object>) q.get("gold"), ctx.categories());
            QueryEngine.Result expected = engine.execute(gold, data);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", q.get("id"));
            row.put("question", q.get("question"));
            long start = System.nanoTime();
            try {
                QuerySpec spec = planner.plan(String.valueOf(q.get("question")), ctx);
                boolean ok = sameResult(expected, engine.execute(spec, data));
                correct += ok ? 1 : 0;
                row.put("correct", ok);
                row.put("query", spec.toMap());
            } catch (RuntimeException ex) {
                errors++;
                row.put("correct", false);
                row.put("error", String.valueOf(ex.getMessage()));
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            totalMs += ms;
            row.put("ms", ms);
            rows.add(row);
            if (pauseMs > 0) {
                Thread.sleep(pauseMs); // free LLM tiers limit tokens per minute
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accuracy", (double) correct / questions.size());
        out.put("correct", correct);
        out.put("errors", errors);
        out.put("avg_ms", totalMs / Math.max(1, questions.size()));
        out.put("results", rows);
        return out;
    }

    static boolean sameResult(QueryEngine.Result a, QueryEngine.Result b) {
        if (a.matched() != b.matched() || Math.abs(a.value() - b.value()) > 0.01) {
            return false;
        }
        if (!a.groups().isEmpty() || !b.groups().isEmpty()) {
            if (a.groups().size() != b.groups().size()) {
                return false;
            }
            for (int i = 0; i < a.groups().size(); i++) {
                QueryEngine.Group x = a.groups().get(i);
                QueryEngine.Group y = b.groups().get(i);
                if (!x.key().equals(y.key()) || Math.abs(x.value() - y.value()) > 0.01) {
                    return false;
                }
            }
        }
        return a.rows().equals(b.rows());
    }
}
