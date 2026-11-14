package com.example.finance.ask;

import com.example.finance.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AskServiceTest {
    private static QueryPlanner failing() {
        return new QueryPlanner() {
            @Override
            public String name() {
                return "llm";
            }

            @Override
            public QuerySpec plan(String question, DatasetContext ctx) {
                throw new IllegalStateException("LLM returned HTTP 429");
            }
        };
    }

    @Test
    void answersWithRulesWhenNoModelIsConfigured() {
        Map<String, Object> r = new AskService(null, "$").ask("How much did I spend on groceries in March?", Fixtures.sample());
        assertEquals("rules", r.get("planned_by"));
        assertNull(r.get("notice"));
        assertTrue(String.valueOf(r.get("answer")).startsWith("You spent $"));
    }

    @Test
    void fallsBackToRulesAndSaysSoWhenTheModelFails() {
        Map<String, Object> r = new AskService(failing(), "$").ask("How much did I spend on groceries in March?", Fixtures.sample());
        assertEquals("rules", r.get("planned_by"));
        assertNotNull(r.get("notice"));
    }

    @Test
    void theAnswerOnlyStatesTheComputedNumber() {
        Map<String, Object> r = new AskService(null, "€").ask("What was my biggest expense?", Fixtures.SMALL);
        assertEquals(1200.0, (double) r.get("value"), 0.001);
        assertTrue(String.valueOf(r.get("answer")).contains("€1,200.00"), String.valueOf(r.get("answer")));
    }
}
