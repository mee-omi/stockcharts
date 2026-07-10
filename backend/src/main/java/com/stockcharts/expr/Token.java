package com.stockcharts.expr;

/** A lexical token. */
public record Token(Token.Type type, String text, double number, int pos) {

    public enum Type {
        NUMBER, IDENT,
        PLUS, MINUS, STAR, SLASH, PERCENT,
        LT, LE, GT, GE, EQ, NE,
        AND, OR, NOT,
        LPAREN, RPAREN, COMMA,
        EOF
    }
}
