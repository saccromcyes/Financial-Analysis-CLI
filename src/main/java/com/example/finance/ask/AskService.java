package com.example.finance.ask;

import com.example.finance.model.Transaction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plan with the language model when available, fall back to rules, execute, and phrase the answer. */
public final class AskService {
    private final QueryPlanner llm;
    private final QueryPlanner rules = new RulePlanner();
    private final QueryEngine engine = new QueryEngine();
    private final AnswerWriter writer;

    public AskService(QueryPlanner llm, String currencySymbol) {
        this.llm = llm;
        this.writer = new AnswerWriter(currencySymbol);
    }

    public boolean llmEnabled() {
        return llm != null;
    }

    public Map<String, Object> ask(String question, List<Transaction> transactions) {
        DatasetContext ctx = DatasetContext.of(transactions);
        long start = System.nanoTime();
        QuerySpec spec;
        String plannedBy;
        String notice = null;
        if (llm != null) {
            try {
                spec = llm.plan(question, ctx);
                plannedBy = llm.name();
            } catch (RuntimeException ex) {
                spec = rules.plan(question, ctx);
                plannedBy = rules.name();
                notice = "The language model was unavailable, so the question was read with keyword rules.";
            }
        } else {
            spec = rules.plan(question, ctx);
            plannedBy = rules.name();
        }
        long planned = System.nanoTime();
        QueryEngine.Result result = engine.execute(spec, transactions);
        long done = System.nanoTime();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", question);
        out.put("answer", writer.write(result));
        out.put("planned_by", plannedBy);
        out.put("notice", notice);
        out.put("query", spec.toMap());
        out.put("value", result.value());
        out.put("matched", result.matched());
        out.put("groups", result.groups().stream().map(g -> Map.of("key", g.key(), "value", g.value(), "count", g.count())).toList());
        out.put("rows", result.rows().stream().map(AskService::row).toList());
        out.put("timings_ms", Map.of("plan", (planned - start) / 1_000_000, "execute", (done - planned) / 1_000_000));
        return out;
    }

    public static Map<String, Object> row(Transaction t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", t.date());
        m.put("description", t.description());
        m.put("category", t.category());
        m.put("type", t.type());
        m.put("amount", t.amount());
        m.put("merchant", t.destination());
        return m;
    }
}
