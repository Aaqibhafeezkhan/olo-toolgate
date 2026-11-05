// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.jsonsdk;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** A closed expression compiler, not an interpreter or a raw-code injection adapter. */
public final class JavaSource {
    public static final String CLASS_NAME = "io.ololabs.toolgate.generated.JsonTools";
    private JavaSource() {}
    static String quote(String value) throws IOException { return JsonProject.JSON.writeValueAsString(value); }

    /** Training hints become the Java SDK's own annotations, so one processor checks and writes them for every SDK. */
    static String training(JsonProject.Training t) throws IOException {
        var out = new StringBuilder("@Training(phrasings=").append(array(t.phrasings()))
                .append(",defaultPhrasings=").append(array(t.defaultPhrasings()))
                .append(",negatives=").append(array(t.negatives())).append(",prompts=");
        return out.append(prompts(t.prompts())).append(')').toString();
    }

    /** Model-validation prompts become {@code @ModelValidation}, checked by the same processor. */
    static String validation(JsonProject.Validation v) throws IOException {
        return "@ModelValidation(prompts=" + prompts(v.prompts()) + ",negatives=" + array(v.negatives()) + ")";
    }

    private static String prompts(java.util.List<JsonProject.Prompt> prompts) throws IOException {
        var out = new StringBuilder("{");
        for (int n = 0; n < prompts.size(); n++) {
            if (n > 0) out.append(',');
            out.append("@TrainingPrompt(prompt=").append(quote(prompts.get(n).prompt()))
                    .append(",arguments=").append(quote(prompts.get(n).arguments())).append(')');
        }
        return out.append('}').toString();
    }

    static String values(JsonProject.Values v) throws IOException {
        var out = new StringBuilder("@TrainingValues(examples=").append(array(v.examples())).append(",phrasings={");
        int n = 0;
        for (var entry : v.phrasings().entrySet()) {
            if (n++ > 0) out.append(',');
            out.append("@ValuePhrasing(value=").append(quote(entry.getKey())).append(",phrasings=").append(array(entry.getValue())).append(')');
        }
        return out.append("})").toString();
    }

    private static String array(java.util.List<String> values) throws IOException {
        var out = new StringBuilder("{");
        for (int n = 0; n < values.size(); n++) {
            if (n > 0) out.append(',');
            out.append(quote(values.get(n)));
        }
        return out.append('}').toString();
    }

    public static String generate(JsonProject project) throws IOException {
        var source = new StringBuilder("package io.ololabs.toolgate.generated;\n")
                .append("import io.ololabs.toolgate.sdk.annotations.*;\n")
                .append("import io.ololabs.toolgate.sdk.context.ToolContext;\n")
                .append("@ToolPackage(id=").append(quote(project.id())).append(",name=").append(quote(project.name()))
                .append(",publisher=").append(quote(project.publisher())).append(")\npublic final class JsonTools {\n");
        int index = 0;
        for (var tool : project.tools()) {
            String record = "Output" + index;
            source.append("public record ").append(record).append('(');
            for (int n = 0; n < tool.outputs().size(); n++) {
                if (n > 0) source.append(',');
                String name = tool.outputs().get(n).name();
                source.append("@com.fasterxml.jackson.annotation.JsonProperty(").append(quote(name)).append(") ")
                        .append("@Param(name=").append(quote(name)).append(",description=\"\") String value").append(n);
            }
            source.append(") {}\n@").append(tool.kind().equals("CLIENT") ? "ClientTool" : "ServerTool")
                    .append("(name=").append(quote(tool.name())).append(",title=").append(quote(tool.title()))
                    .append(",description=").append(quote(tool.description()))
                    .append(",effect=EffectSafety.READ_ONLY,stateless=true)\n");
            if (tool.training() != null) source.append(training(tool.training())).append('\n');
            if (tool.modelValidation() != null) source.append(validation(tool.modelValidation())).append('\n');
            Map<String, String> defaults = new LinkedHashMap<>();
            boolean complete = true;
            for (var input : tool.inputs()) {
                if (input.defaultValue() == null) complete = false;
                else defaults.put(input.name(), input.defaultValue());
            }
            if (complete) {
                var expected = new LinkedHashMap<String, String>();
                for (var output : tool.outputs()) {
                    var result = new StringBuilder();
                    for (var part : output.parts()) result.append(part.literal() != null ? part.literal() : defaults.get(part.input()));
                    expected.put(output.name(), result.toString());
                }
                source.append("@Example(arguments=").append(quote(JsonProject.JSON.writeValueAsString(defaults)))
                        .append(",expectedOutput=").append(quote(JsonProject.JSON.writeValueAsString(expected))).append(")\n");
            }
            source.append("public ").append(record).append(" tool").append(index++).append('(');
            for (int n = 0; n < tool.inputs().size(); n++) {
                var input = tool.inputs().get(n);
                source.append("@Param(name=").append(quote(input.name())).append(",description=").append(quote(input.description()))
                        .append(",maxLength=").append(input.maxLength());
                if (input.defaultValue() != null) source.append(",defaultValue=").append(quote(input.defaultValue()));
                source.append(") ");
                if (input.training() != null) source.append(values(input.training())).append(' ');
                source.append("String arg").append(n).append(',');
            }
            source.append("ToolContext context) {\n");
            for (var event : tool.events()) {
                if (event.fraction() != null) source.append("context.progress(").append(event.fraction()).append(',').append(quote(event.message())).append(");\n");
                else source.append("context.log().").append(event.level()).append('(').append(quote(event.message())).append(");\n");
            }
            source.append("return new ").append(record).append('(');
            for (int n = 0; n < tool.outputs().size(); n++) {
                if (n > 0) source.append(',');
                source.append("new StringBuilder()");
                for (var part : tool.outputs().get(n).parts()) {
                    source.append(".append(");
                    if (part.literal() != null) source.append(quote(part.literal()));
                    else {
                        int argument = 0;
                        while (!tool.inputs().get(argument).name().equals(part.input())) argument++;
                        source.append("arg").append(argument);
                    }
                    source.append(')');
                }
                source.append(".toString()");
            }
            source.append(");\n}\n");
        }
        return source.append("}\n").toString();
    }
}
