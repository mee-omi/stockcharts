package com.stockcharts.expr;

/** Thrown for lex/parse/eval errors in a user expression (maps to HTTP 400). */
public class ExpressionException extends RuntimeException {
    public ExpressionException(String message) {
        super(message);
    }
}
