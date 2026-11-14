package com.example.finance.eval;

import com.example.finance.Fixtures;
import com.example.finance.ask.DatasetContext;
import com.example.finance.ask.QueryEngine;
import com.example.finance.ask.QuerySpec;
import com.example.finance.ask.RulePlanner;
import com.example.finance.model.Transaction;
import com.example.finance.util.Json;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerEvalTest {
    @Test
    void differentlyWrittenQueriesWithTheSameResultCountAsCorrect() {
        List<Transaction> data = Fixtures.SMALL;
        QueryEngine engine = new QueryEngine();
        Map<String, Object> a = Map.of("metric", "sum", "type", "DEBIT", "categories", List.of("Groceries"));
        Map<String, Object> b = Map.of("metric", "sum", "merchant", "Fresh Market");
        Map<String, Object> c = Map.of("metric", "sum", "type", "DEBIT");
        var cats = DatasetContext.of(data).categories();
        QueryEngine.Result ra = engine.execute(QuerySpec.fromMap(a, cats), data);
        assertTrue(PlannerEval.sameResult(ra, engine.execute(QuerySpec.fromMap(b, cats), data)));
        assertFalse(PlannerEval.sameResult(ra, engine.execute(QuerySpec.fromMap(c, cats), data)));
    }

    @Test
    void everyGoldQueryIsValidAndTheRulePlannerClearsTheBar() throws Exception {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> questions = (List<Map<String, Object>>) Json.parse(Files.readString(Path.of("eval", "questions.json")));
        List<Transaction> data = Fixtures.sample();
        Map<String, Object> score = PlannerEval.score(new RulePlanner(), questions, data, DatasetContext.of(data), 0);
        assertEquals(0, score.get("errors"));
        assertTrue((double) score.get("accuracy") >= 0.8, "rule planner accuracy " + score.get("accuracy"));
    }
}
