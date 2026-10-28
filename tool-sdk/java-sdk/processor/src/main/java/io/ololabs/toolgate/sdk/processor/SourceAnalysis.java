// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.processor;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * The build-time analyser of http.md §3 and java.md, over the javac Tree API. It proves every URL-style convenience call
 * ({@code http().get(destination, template)} and its siblings): both arguments are string literals, the call sits in
 * a tool handler, and method, destination and template match exactly one declared route that the handler's tool
 * lists. Anything it cannot prove is a compile error at the call site, as is reflective access to the network API.
 * It also checks {@code get*Value} calls with a literal name: the name must be declared for the tool, and the getter
 * must read its type.
 */
final class SourceAnalysis {
    /** What one tool handler may use. Value maps are name to value type, as runtime DeclaredValues names them. */
    record Facts(String tool, Set<String> routes, Map<String, String> inputs, Map<String, String> globals, Map<String, String> secrets,
            Map<String, String> claims) {}

    /** A declared route, as the analyser compares it: method, destination and the template without its patterns. */
    record Route(String id, String method, String destination, String template) {}

    static final String CONTEXT = "io.ololabs.toolgate.sdk.context.";
    private static final Set<String> CONVENIENCE = Set.of("get", "post", "put", "patch", "delete", "head");
    private static final Set<String> NETWORK_TYPES = Set.of(CONTEXT + "HttpClient", CONTEXT + "RouteCall", CONTEXT + "TcpConnector");
    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)(:[^{}]*(\\{[^{}]*\\}[^{}]*)*)?\\}");
    /** The value types each getter reads, exactly as the runtime's DeclaredValues accepts them. */
    static final Map<String, Set<String>> GETTERS = Map.ofEntries(
            Map.entry("getStringValue", Set.of("STRING", "ENUM", "URI", "DATE", "DATE_TIME", "DURATION")),
            Map.entry("getIntValue", Set.of("INT")),
            Map.entry("getLongValue", Set.of("INT", "LONG")),
            Map.entry("getNumberValue", Set.of("INT", "LONG", "NUMBER")),
            Map.entry("getBooleanValue", Set.of("BOOLEAN")),
            Map.entry("getDateValue", Set.of("DATE")),
            Map.entry("getDateTimeValue", Set.of("DATE_TIME")),
            Map.entry("getDurationValue", Set.of("DURATION")),
            Map.entry("getObjectValue", Set.of("OBJECT")),
            Map.entry("getListValue", Set.of("ARRAY")),
            Map.entry("getBinaryValue", Set.of("BINARY")));

    private final Trees trees;
    private final List<Route> routes;
    private final Map<String, Facts> handlers;

    /** {@code handlers} is keyed by {@link #key}, because javac makes new elements after annotation processing. */
    SourceAnalysis(ProcessingEnvironment env, Collection<Route> routes, Map<String, Facts> handlers) {
        this.trees = Trees.instance(env);
        this.routes = new ArrayList<>(routes);
        this.handlers = handlers;
    }

    /** A handler's identity across compiler phases: its class, name and erased parameter types. */
    static String key(ExecutableElement method) {
        StringBuilder k = new StringBuilder(((TypeElement) method.getEnclosingElement()).getQualifiedName()).append('#')
                .append(method.getSimpleName()).append('(');
        for (javax.lang.model.element.VariableElement p : method.getParameters()) k.append(p.asType().toString().replaceAll("<.*>", "")).append(',');
        return k.append(')').toString();
    }

    static String withoutPatterns(String template) {
        return VARIABLE.matcher(template).replaceAll(m -> Matcher.quoteReplacement("{" + m.group(1) + "}"));
    }

    /** Analyses one attributed top-level type; errors go to the compiler's diagnostics. */
    void analyse(TypeElement type) {
        TreePath path = trees.getPath(type);
        if (path != null) new Scanner(path.getCompilationUnit()).scan(path, null);
    }

    private final class Scanner extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private Facts facts;

        Scanner(CompilationUnitTree unit) {
            this.unit = unit;
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            // A nested or local class body is not the handler's own code.
            Facts outer = facts;
            facts = null;
            try {
                return super.visitClass(tree, unused);
            } finally {
                facts = outer;
            }
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            Facts outer = facts;
            Element method = trees.getElement(getCurrentPath());
            facts = method instanceof ExecutableElement e && e.getEnclosingElement() instanceof TypeElement ? handlers.get(key(e)) : null;
            try {
                return super.visitMethod(tree, unused);
            } finally {
                facts = outer;
            }
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
            if (tree.getIdentifier().contentEquals("class")) {
                Element type = trees.getElement(new TreePath(getCurrentPath(), tree.getExpression()));
                if (type instanceof TypeElement t && NETWORK_TYPES.contains(t.getQualifiedName().toString())) {
                    error(tree, "reflective access to " + t.getSimpleName() + " cannot be analysed; call the network API directly");
                }
            }
            return super.visitMemberSelect(tree, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
            Element called = trees.getElement(getCurrentPath());
            if (called instanceof ExecutableElement method && method.getEnclosingElement() instanceof TypeElement owner) {
                String type = owner.getQualifiedName().toString();
                String name = method.getSimpleName().toString();
                if (type.equals(CONTEXT + "HttpClient") && CONVENIENCE.contains(name) && tree.getArguments().size() == 2) {
                    convenience(tree, name.toUpperCase(Locale.ROOT));
                } else if (GETTERS.containsKey(name) && !tree.getArguments().isEmpty()
                        && Set.of(CONTEXT + "ToolContext", CONTEXT + "Values", CONTEXT + "Jwt").contains(type)) {
                    value(tree, type, name);
                }
            }
            return super.visitMethodInvocation(tree, unused);
        }

        private void convenience(MethodInvocationTree call, String method) {
            String destination = literal(call.getArguments().get(0));
            String template = literal(call.getArguments().get(1));
            if (destination == null || template == null) {
                error(call, "a convenience call needs string literals for the destination and the path template, so the route can be"
                        + " proven at build time; use http().route(id) instead");
                return;
            }
            if (facts == null) {
                error(call, "a convenience call outside a tool handler cannot be proven; make it in the handler or use http().route(id)");
                return;
            }
            String wanted = withoutPatterns(template);
            List<Route> matches = routes.stream()
                    .filter(r -> r.method().equals(method) && r.destination().equals(destination) && r.template().equals(wanted))
                    .collect(Collectors.toList());
            if (matches.isEmpty()) {
                String candidates = routes.stream().filter(r -> r.destination().equals(destination))
                        .map(r -> r.id() + " (" + r.method() + " " + r.template() + ")").collect(Collectors.joining(", "));
                error(call, method + " " + destination + " " + template + " matches no declared route"
                        + (candidates.isEmpty() ? "; destination " + destination + " has no routes" : "; candidates: " + candidates));
            } else if (matches.size() > 1) {
                error(call, method + " " + destination + " " + template + " matches more than one route: "
                        + matches.stream().map(Route::id).collect(Collectors.joining(" and ")));
            } else if (!facts.routes().contains(matches.get(0).id())) {
                error(call, method + " " + destination + " " + template + " is route " + matches.get(0).id() + ", which tool "
                        + facts.tool() + " does not list in routes");
            }
        }

        private void value(MethodInvocationTree call, String type, String getter) {
            String name = literal(call.getArguments().get(0));
            if (name == null || facts == null) return;
            String kind;
            Map<String, String> declared;
            if (type.equals(CONTEXT + "ToolContext")) {
                kind = "input";
                declared = facts.inputs();
            } else {
                // Only a direct context.global() / secret() / jwt() receiver says which values these are.
                if (!(call.getMethodSelect() instanceof MemberSelectTree select)
                        || !(select.getExpression() instanceof MethodInvocationTree receiver)) {
                    return;
                }
                Element accessor = trees.getElement(new TreePath(getCurrentPath(), receiver));
                if (!(accessor instanceof ExecutableElement a) || !(a.getEnclosingElement() instanceof TypeElement t)
                        || !t.getQualifiedName().contentEquals(CONTEXT + "ToolContext")) {
                    return;
                }
                switch (a.getSimpleName().toString()) {
                    case "global" -> {
                        kind = "global";
                        declared = facts.globals();
                    }
                    case "secret" -> {
                        kind = "secret";
                        declared = facts.secrets();
                    }
                    case "jwt" -> {
                        kind = "claim";
                        declared = facts.claims();
                    }
                    default -> {
                        return;
                    }
                }
            }
            String valueType = declared.get(name);
            if (valueType == null) {
                error(call, "tool " + facts.tool() + " declares no " + kind + " " + name
                        + (declared.isEmpty() ? "" : "; declared: " + String.join(", ", declared.keySet())));
            } else if (!GETTERS.get(getter).contains(valueType)) {
                error(call, kind + " " + name + " of tool " + facts.tool() + " is " + valueType + "; " + getter + " does not read it");
            }
        }

        private String literal(ExpressionTree tree) {
            return tree instanceof LiteralTree l && l.getValue() instanceof String s ? s : null;
        }

        private void error(Tree at, String message) {
            trees.printMessage(Diagnostic.Kind.ERROR, "toolgate: " + message, at, unit);
        }
    }
}
