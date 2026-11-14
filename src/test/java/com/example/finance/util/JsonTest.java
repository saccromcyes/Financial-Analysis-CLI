package com.example.finance.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {
    @Test
    void parsesNestedValues() {
        Map<String, Object> m = Json.parseObject("{\"a\": [1, 2.5, true, null], \"b\": {\"c\": \"d\\u00e9\\n\"}}");
        List<?> a = (List<?>) m.get("a");
        assertEquals(4, a.size());
        assertEquals(2.5, ((Number) a.get(1)).doubleValue(), 0.0);
        assertEquals(Boolean.TRUE, a.get(2));
        assertEquals("dé\n", ((Map<?, ?>) m.get("b")).get("c"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": 1"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": 1} trailing"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1, 2,]"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[1]"));
    }

    @Test
    void lenientParsingFindsTheObjectInsideProse() {
        assertEquals("sum", Json.parseLenientObject("Sure! ```json\n{\"metric\": \"sum\"}\n``` Hope that helps.").get("metric"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseLenientObject("no json here"));
    }

    @Test
    void writesHtmlSafeStringsAndTidyNumbers() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "</script><b>&\"");
        m.put("whole", 3.0);
        m.put("money", 12.345);
        m.put("date", LocalDate.of(2025, 3, 1));
        m.put("nan", Double.NaN);
        String out = Json.write(m);
        assertFalse(out.contains("<"), out);
        assertFalse(out.contains(">"), out);
        assertTrue(out.contains("\"whole\":3,"), out);
        assertTrue(out.contains("\"money\":12.35"), out);
        assertTrue(out.contains("\"date\":\"2025-03-01\""), out);
        assertTrue(out.contains("\"nan\":null"), out);
        assertEquals("</script><b>&\"", Json.parseObject(out).get("s"));
    }

    @Test
    void deepNestingIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(10_000) + "]".repeat(10_000)));
    }
}
