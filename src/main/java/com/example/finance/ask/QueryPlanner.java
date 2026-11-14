package com.example.finance.ask;

/** Turns a plain-English question into a {@link QuerySpec}. */
public interface QueryPlanner {
    String name();

    QuerySpec plan(String question, DatasetContext context);
}
