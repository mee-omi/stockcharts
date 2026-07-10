package com.stockcharts.indicator;

import com.stockcharts.expr.CompiledExpr;
import com.stockcharts.expr.ExpressionException;
import com.stockcharts.expr.ExpressionService;
import com.stockcharts.indicator.dto.CustomIndicatorDto;
import com.stockcharts.ta.PriceSeries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * CRUD for custom indicators, plus the {@link IndicatorEngine.CustomIndicatorResolver}
 * implementation that evaluates a custom indicator's expression (resolving nested custom
 * references, with cycle detection) into an {@link com.stockcharts.indicator.dto.IndicatorResult}.
 */
@Service
public class CustomIndicatorService implements IndicatorEngine.CustomIndicatorResolver {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final CustomIndicatorRepository repository;
    private final ExpressionService expr;

    public CustomIndicatorService(CustomIndicatorRepository repository, ExpressionService expr) {
        this.repository = repository;
        this.expr = expr;
    }

    public List<CustomIndicator> list() {
        return repository.findAll();
    }

    public CustomIndicator get(String name) {
        return repository.findById(name)
                .orElseThrow(() -> new NoSuchElementException("Custom indicator not found: " + name));
    }

    @Transactional
    public CustomIndicator create(CustomIndicatorDto dto) {
        validateName(dto.name());
        if (repository.existsById(dto.name())) {
            throw new IllegalArgumentException("Custom indicator already exists: " + dto.name());
        }
        validateExpressionOrThrow(dto.expression(), dto.name());
        return repository.save(new CustomIndicator(
                dto.name(), dto.expression(), dto.overlay(), dto.description()));
    }

    @Transactional
    public CustomIndicator update(String name, CustomIndicatorDto dto) {
        CustomIndicator ci = get(name);
        validateExpressionOrThrow(dto.expression(), name);
        ci.setExpression(dto.expression());
        ci.setOverlay(dto.overlay());
        ci.setDescription(dto.description());
        return repository.save(ci);
    }

    @Transactional
    public void delete(String name) {
        if (!repository.existsById(name)) {
            throw new NoSuchElementException("Custom indicator not found: " + name);
        }
        repository.deleteById(name);
    }

    public ExpressionService.ValidationResult validate(String expression) {
        return expr.validate(expression, existingNames());
    }

    /** All custom indicator names — used to whitelist references during expression validation. */
    public Set<String> existingNames() {
        return repository.findAll().stream()
                .map(CustomIndicator::getName).collect(Collectors.toSet());
    }

    // ---- resolver (used by IndicatorEngine for non-built-in indicator specs) ----

    @Override
    public com.stockcharts.indicator.dto.IndicatorResult resolve(
            String name, PriceSeries series, long[] times) {
        CustomIndicator ci = repository.findById(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown indicator: " + name));
        double[] values = computeSeries(name, series, new HashSet<>());
        return new com.stockcharts.indicator.dto.IndicatorResult(
                ci.getName(), "CUSTOM", ci.isOverlay(),
                List.of(IndicatorEngine.line("value", values, times)));
    }

    /** Compute a custom indicator's series against the given price data (used by the screener). */
    public double[] compute(String name, PriceSeries series) {
        return computeSeries(name, series, new HashSet<>());
    }

    private double[] computeSeries(String name, PriceSeries series, Set<String> visiting) {
        if (!visiting.add(name)) {
            throw new ExpressionException("Circular custom-indicator reference at '" + name + "'");
        }
        CustomIndicator ci = repository.findById(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown indicator: " + name));
        CompiledExpr compiled = expr.compile(ci.getExpression());
        Map<String, double[]> customs = new HashMap<>();
        for (String ref : compiled.customReferences()) {
            customs.put(ref, computeSeries(ref, series, visiting));
        }
        double[] result = expr.evaluate(compiled, series, customs);
        visiting.remove(name);
        return result;
    }

    // ---- validation helpers ----

    private void validateName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Name must be a valid identifier (letters, digits, underscore; not starting with a digit)");
        }
        if (IndicatorType.fromCodeOrNull(name) != null) {
            throw new IllegalArgumentException("Name clashes with a built-in indicator: " + name);
        }
        if (CompiledExpr.PRICE_FIELDS.contains(name.toLowerCase())) {
            throw new IllegalArgumentException("Name clashes with a price field: " + name);
        }
    }

    private void validateExpressionOrThrow(String expression, String selfName) {
        Set<String> known = existingNames();
        known.remove(selfName); // a custom indicator may not reference itself
        ExpressionService.ValidationResult v = expr.validate(expression, known);
        if (!v.valid()) {
            throw new ExpressionException(v.error());
        }
    }
}
