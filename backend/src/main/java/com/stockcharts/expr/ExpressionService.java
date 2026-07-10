package com.stockcharts.expr;

import com.stockcharts.ta.PriceSeries;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compiles and evaluates user expressions for custom indicators and screeners.
 * Sandboxed: only whitelisted price fields and functions are accessible — no code execution.
 */
@Service
public class ExpressionService {

    private static final int MAX_LENGTH = 2000;
    private final Map<String, CompiledExpr> cache = new ConcurrentHashMap<>();

    public record ValidationResult(boolean valid, String error,
                                   List<String> variables, List<String> customReferences) {
    }

    /** Parse (and cache) an expression. Throws {@link ExpressionException} on syntax error. */
    public CompiledExpr compile(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new ExpressionException("Expression is empty");
        }
        if (expression.length() > MAX_LENGTH) {
            throw new ExpressionException("Expression too long (max " + MAX_LENGTH + " chars)");
        }
        return cache.computeIfAbsent(expression.trim(), src -> {
            Expr root = new Parser(new Lexer(src).tokenize()).parse();
            return new CompiledExpr(src, root);
        });
    }

    public double[] evaluate(CompiledExpr compiled, PriceSeries series, Map<String, double[]> customs) {
        return compiled.eval(new EvalContext(series, customs));
    }

    public double[] evaluate(String expression, PriceSeries series, Map<String, double[]> customs) {
        return evaluate(compile(expression), series, customs);
    }

    /**
     * Validate an expression by compiling and evaluating it on a synthetic series. Any referenced
     * custom-indicator names are stubbed so validation checks syntax/functions, not their existence.
     */
    public ValidationResult validate(String expression) {
        return validate(expression, Set.of());
    }

    /**
     * Validate against a synthetic series. Identifiers that are neither price fields nor in
     * {@code knownCustoms} are rejected (catches typos); known customs are stubbed.
     */
    public ValidationResult validate(String expression, Set<String> knownCustoms) {
        try {
            CompiledExpr compiled = compile(expression);
            for (String ref : compiled.customReferences()) {
                if (!knownCustoms.contains(ref)) {
                    return new ValidationResult(false, "Unknown identifier '" + ref + "'",
                            List.of(), List.of());
                }
            }
            PriceSeries synthetic = syntheticSeries(60);
            Map<String, double[]> stubs = new ConcurrentHashMap<>();
            for (String ref : compiled.customReferences()) {
                stubs.put(ref, ramp(synthetic.size()));
            }
            double[] result = evaluate(compiled, synthetic, stubs);
            if (result.length != synthetic.size()) {
                return new ValidationResult(false, "Result length mismatch", List.of(), List.of());
            }
            return new ValidationResult(true, null,
                    List.copyOf(compiled.variables()), List.copyOf(compiled.customReferences()));
        } catch (ExpressionException e) {
            return new ValidationResult(false, e.getMessage(), List.of(), List.of());
        } catch (RuntimeException e) {
            return new ValidationResult(false, "Invalid expression: " + e.getMessage(), List.of(), List.of());
        }
    }

    public Set<String> customReferences(String expression) {
        return compile(expression).customReferences();
    }

    // ---- synthetic data for validation ----
    private static PriceSeries syntheticSeries(int n) {
        long[] time = new long[n];
        double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n], v = new double[n];
        double price = 100;
        for (int i = 0; i < n; i++) {
            time[i] = i * 60L;
            double close = price + Math.sin(i / 4.0) * 3 + i * 0.05;
            o[i] = price;
            c[i] = close;
            h[i] = Math.max(o[i], close) + 1;
            l[i] = Math.min(o[i], close) - 1;
            v[i] = 1000 + i;
            price = close;
        }
        return new PriceSeries(time, o, h, l, c, v);
    }

    private static double[] ramp(int n) {
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = 100 + i;
        return r;
    }
}
