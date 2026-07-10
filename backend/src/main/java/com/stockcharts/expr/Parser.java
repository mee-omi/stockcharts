package com.stockcharts.expr;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser with standard precedence:
 * or &lt; and &lt; comparison &lt; additive &lt; multiplicative &lt; unary &lt; primary.
 */
final class Parser {

    private final List<Token> tokens;
    private int pos = 0;

    Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    Expr parse() {
        Expr e = parseOr();
        expect(Token.Type.EOF);
        return e;
    }

    private Expr parseOr() {
        Expr left = parseAnd();
        while (match(Token.Type.OR)) {
            left = new Expr.Binary("or", left, parseAnd());
        }
        return left;
    }

    private Expr parseAnd() {
        Expr left = parseComparison();
        while (match(Token.Type.AND)) {
            left = new Expr.Binary("and", left, parseComparison());
        }
        return left;
    }

    private Expr parseComparison() {
        Expr left = parseAdditive();
        while (true) {
            String op = null;
            if (match(Token.Type.GT)) op = ">";
            else if (match(Token.Type.GE)) op = ">=";
            else if (match(Token.Type.LT)) op = "<";
            else if (match(Token.Type.LE)) op = "<=";
            else if (match(Token.Type.EQ)) op = "==";
            else if (match(Token.Type.NE)) op = "!=";
            if (op == null) break;
            left = new Expr.Binary(op, left, parseAdditive());
        }
        return left;
    }

    private Expr parseAdditive() {
        Expr left = parseMultiplicative();
        while (true) {
            if (match(Token.Type.PLUS)) left = new Expr.Binary("+", left, parseMultiplicative());
            else if (match(Token.Type.MINUS)) left = new Expr.Binary("-", left, parseMultiplicative());
            else break;
        }
        return left;
    }

    private Expr parseMultiplicative() {
        Expr left = parseUnary();
        while (true) {
            if (match(Token.Type.STAR)) left = new Expr.Binary("*", left, parseUnary());
            else if (match(Token.Type.SLASH)) left = new Expr.Binary("/", left, parseUnary());
            else if (match(Token.Type.PERCENT)) left = new Expr.Binary("%", left, parseUnary());
            else break;
        }
        return left;
    }

    private Expr parseUnary() {
        if (match(Token.Type.MINUS)) return new Expr.Unary("-", parseUnary());
        if (match(Token.Type.NOT)) return new Expr.Unary("not", parseUnary());
        return parsePrimary();
    }

    private Expr parsePrimary() {
        Token t = peek();
        if (match(Token.Type.NUMBER)) {
            return new Expr.Num(t.number());
        }
        if (match(Token.Type.LPAREN)) {
            Expr e = parseOr();
            expect(Token.Type.RPAREN);
            return e;
        }
        if (t.type() == Token.Type.IDENT) {
            advance();
            if (match(Token.Type.LPAREN)) {
                List<Expr> args = new ArrayList<>();
                if (peek().type() != Token.Type.RPAREN) {
                    args.add(parseOr());
                    while (match(Token.Type.COMMA)) {
                        args.add(parseOr());
                    }
                }
                expect(Token.Type.RPAREN);
                return new Expr.Call(t.text(), args);
            }
            return new Expr.Var(t.text());
        }
        throw new ExpressionException("Unexpected token '" + t.text() + "' at position " + t.pos());
    }

    // ---- token helpers ----
    private Token peek() {
        return tokens.get(pos);
    }

    private void advance() {
        if (pos < tokens.size() - 1) pos++;
    }

    private boolean match(Token.Type type) {
        if (peek().type() == type) {
            advance();
            return true;
        }
        return false;
    }

    private void expect(Token.Type type) {
        if (peek().type() != type) {
            throw new ExpressionException("Expected " + type + " but found '" + peek().text()
                    + "' at position " + peek().pos());
        }
        if (type != Token.Type.EOF) advance();
    }
}
