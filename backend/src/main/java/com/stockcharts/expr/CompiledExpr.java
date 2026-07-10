package com.stockcharts.expr;

import java.util.LinkedHashSet;
import java.util.Set;

/** A parsed, reusable expression. Safe to evaluate against many price series. */
public final class CompiledExpr {

    /** Price-field identifiers handled natively by the evaluator. */
    public static final Set<String> PRICE_FIELDS = Set.of(
            "open", "high", "low", "close", "volume", "hl2", "hlc3", "typical", "ohlc4");

    private final String source;
    private final Expr root;
    private final Set<String> variables;

    CompiledExpr(String source, Expr root) {
        this.source = source;
        this.root = root;
        Set<String> vars = new LinkedHashSet<>();
        collectVars(root, vars);
        this.variables = vars;
    }

    public double[] eval(EvalContext ctx) {
        return Evaluator.eval(root, ctx);
    }

    public String source() {
        return source;
    }

    /** All identifier names referenced (price fields + custom indicator names). */
    public Set<String> variables() {
        return variables;
    }

    /** Referenced names that are NOT built-in price fields — candidate custom indicators. */
    public Set<String> customReferences() {
        Set<String> out = new LinkedHashSet<>();
        for (String v : variables) {
            if (!PRICE_FIELDS.contains(v.toLowerCase())) {
                out.add(v);
            }
        }
        return out;
    }

    private static void collectVars(Expr e, Set<String> out) {
        switch (e) {
            case Expr.Num ignored -> {
            }
            case Expr.Var v -> out.add(v.name());
            case Expr.Unary u -> collectVars(u.operand(), out);
            case Expr.Binary b -> {
                collectVars(b.left(), out);
                collectVars(b.right(), out);
            }
            case Expr.Call c -> c.args().forEach(arg -> collectVars(arg, out));
        }
    }
}
