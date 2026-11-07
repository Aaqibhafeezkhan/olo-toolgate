// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import java.util.Map;
import java.util.Set;

/**
 * Subscription filters in CEL ({@code dev.cel}), compiled against the owner's readable view (plan 12.2 rule 1 and
 * 15.2; triggers modules 11 and 12).
 *
 * <p>{@code event} is the only variable. A filter may select only fields the owner can read: the envelope ({@code id},
 * {@code type}, {@code source}, {@code time}, {@code tgclassification}) always, and {@code subject} and {@code data}
 * only when the owner's clearance covers the events' label. Any other use of {@code event} is a compile error: key
 * indexing, {@code size(event)}, comprehensions over it, or passing it whole to a function. So the outcome of a filter
 * can depend only on readable data, and at run time the activation holds only the view.
 *
 * <p>Limits (plan 15.2): at most 4096 characters, AST depth 32 and 512 nodes; no comprehensions (macros such as
 * {@code exists} and {@code all}), so evaluation is linear in the expression (the static cost bound); a boolean result.
 */
public final class TriggerFilters {
    static final int MAX_LENGTH = 4096, MAX_DEPTH = 32, MAX_NODES = 512;
    static final Set<String> ENVELOPE = Set.of("id", "type", "source", "time", "tgclassification");
    static final Set<String> READABLE = Set.of("id", "type", "source", "time", "tgclassification", "subject", "data");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CelOptions OPTIONS = CelOptions.current().enableHeterogeneousNumericComparisons(true).maxParseRecursionDepth(MAX_DEPTH).maxExpressionCodePointSize(MAX_LENGTH).comprehensionMaxIterations(0).build();
    private static final CelCompiler COMPILER = CelCompilerFactory.standardCelCompilerBuilder()
        .setOptions(OPTIONS).addVar("event", MapType.create(SimpleType.STRING, SimpleType.DYN)).setResultType(SimpleType.BOOL).build();
    private static final CelRuntime RUNTIME = CelRuntimeFactory.plannerRuntimeBuilder().setOptions(OPTIONS).build();
    private TriggerFilters() {}

    /** A compiled filter; {@link #matches} never throws for event data, it returns false. */
    public record Filter(String source, CelRuntime.Program program) {
        public boolean matches(JsonNode view) {
            try {
                @SuppressWarnings("unchecked") Map<String, Object> event = JSON.convertValue(view, Map.class);
                return Boolean.TRUE.equals(program.eval(Map.of("event", event)));
            } catch (CelEvaluationException | RuntimeException failed) { return false; }
        }
    }

    /** Compiles a filter for an owner who can ({@code readable}) or cannot read the events' data; 400 on any violation. */
    public static Filter compile(String expression, boolean readable) {
        if (expression == null || expression.isBlank()) return null;
        if (expression.length() > MAX_LENGTH) throw TriggerService.invalid("Filter exceeds " + MAX_LENGTH + " characters");
        CelAbstractSyntaxTree ast;
        try { ast = COMPILER.compile(expression).getAst(); }
        catch (CelValidationException invalid) { throw TriggerService.invalid("Filter does not compile: " + bounded(invalid.getMessage())); }
        catch (RuntimeException invalid) { throw TriggerService.invalid("Filter does not compile"); }
        if (!SimpleType.BOOL.equals(ast.getResultType())) throw TriggerService.invalid("A filter is a boolean expression");
        int[] nodes = {0};
        check(ast.getExpr(), readable ? READABLE : ENVELOPE, 0, nodes, false);
        try { return new Filter(expression, RUNTIME.createProgram(ast)); }
        catch (Exception invalid) { throw TriggerService.invalid("Filter does not compile"); }
    }

    private static void check(CelExpr expr, Set<String> allowed, int depth, int[] nodes, boolean selected) {
        if (++nodes[0] > MAX_NODES) throw TriggerService.invalid("Filter exceeds " + MAX_NODES + " expression nodes");
        if (depth > MAX_DEPTH) throw TriggerService.invalid("Filter exceeds nesting depth " + MAX_DEPTH);
        switch (expr.getKind()) {
            case IDENT -> {
                if (expr.ident().name().equals("event") && !selected)
                    throw TriggerService.invalid("A filter may only select readable fields of event, such as event.type");
            }
            case SELECT -> {
                var select = expr.select();
                var operand = select.operand();
                if (operand.getKind() == CelExpr.ExprKind.Kind.IDENT && operand.ident().name().equals("event")) {
                    if (!allowed.contains(select.field()))
                        throw TriggerService.invalid("event." + select.field() + " is not readable for this subscription's owner");
                    return;
                }
                check(operand, allowed, depth + 1, nodes, false);
            }
            case CALL -> {
                var call = expr.call();
                if (call.target().isPresent()) check(call.target().get(), allowed, depth + 1, nodes, false);
                for (var argument : call.args()) check(argument, allowed, depth + 1, nodes, false);
            }
            case LIST -> { for (var element : expr.list().elements()) check(element, allowed, depth + 1, nodes, false); }
            case MAP -> {
                for (var entry : expr.map().entries()) { check(entry.key(), allowed, depth + 1, nodes, false); check(entry.value(), allowed, depth + 1, nodes, false); }
            }
            case STRUCT -> { for (var entry : expr.struct().entries()) check(entry.value(), allowed, depth + 1, nodes, false); }
            case COMPREHENSION -> throw TriggerService.invalid("Filters may not use comprehensions (exists, all, map, filter)");
            default -> { }
        }
    }

    private static String bounded(String message) {
        String line = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        return line.length() > 200 ? line.substring(0, 200) : line;
    }
}
