package com.stockcharts.expr;

import java.util.List;

/** Expression AST. Each node evaluates to a {@code double[]} series. */
public sealed interface Expr permits Expr.Num, Expr.Var, Expr.Call, Expr.Unary, Expr.Binary {

    /** Numeric literal (broadcast to a constant series). */
    record Num(double value) implements Expr {
    }

    /** A named series: a price field (open/high/low/close/volume/hl2/…) or a custom indicator. */
    record Var(String name) implements Expr {
    }

    /** A function call, e.g. sma(close, 20). */
    record Call(String name, List<Expr> args) implements Expr {
    }

    /** Unary operator: "-" or "not". */
    record Unary(String op, Expr operand) implements Expr {
    }

    /** Binary operator: arithmetic, comparison, or logical. */
    record Binary(String op, Expr left, Expr right) implements Expr {
    }
}
