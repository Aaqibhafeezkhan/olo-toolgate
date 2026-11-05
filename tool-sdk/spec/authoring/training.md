# Training hints

## Purpose

Training hints teach a model when and how to call a tool: how people ask for it, realistic values for its inputs, and look-alike requests that must not call it. They are declared **on the tool itself**, next to its name, description and inputs, in every SDK. The build checks them against the tool and generates `training/annotations.json` from them. There is no hand-written annotations file: `toolgate pack` refuses one, so the hints cannot drift from the tools.

Hints are never part of a descriptor or a package, so editing one changes no `toolDescriptorDigest`, `descriptorSetDigest` or (for Java and JSON tools) `packageDigest`. In Python the hints live in the tool's source, which is packaged, so the package digest moves with any source edit, as it would for a changed docstring.

## 1. What a tool declares

| Hint | Meaning |
|---|---|
| `phrasings` | Requests that call the tool. `{input}` placeholders are filled with example values. |
| `defaultPhrasings` | Requests that call the tool with no arguments, or only its required ones. |
| `negatives` | Look-alike requests that must not call the tool, such as general questions on the same topic. No placeholders. |
| `prompts` (written as `examples`) | Complete request and call pairs: `{"prompt": "...", "arguments": {...}}`. |
| per input: `examples` | Realistic values of the input's type. |
| per input: `phrasings` | Words for particular values, keyed by the value: `true`/`false`, an enum constant, or the value as text. |

## 2. Declaring them

**Java**: `@Training` on the handler, `@TrainingValues` on the parameter beside `@Param` (or in `@Training(parameters = ...)` with a `name`, for grouped `@ToolVariables` inputs). Source retention: the hints are not in the class files.

```java
@ServerTool(name = "query", title = "Query a CSV file", description = "...")
@Training(
        phrasings = {"Show the rows of {file} where {where}", "Top {limit} rows of {file}"},
        defaultPhrasings = "Read {file}",
        negatives = {"How do I filter rows in pandas?"},
        prompts = @TrainingPrompt(prompt = "Show me orders.csv", arguments = "{\"file\":\"orders.csv\"}"))
public Rows query(
        @Param(description = "CSV file") @TrainingValues(examples = {"orders.csv", "customers.csv"}) String file,
        @Param(description = "Row limit", required = false) @TrainingValues(examples = {"5", "10"}) Integer limit,
        @Param(description = "Largest first", required = false)
        @TrainingValues(phrasings = @ValuePhrasing(value = "true", phrasings = {"largest first", "descending"})) Boolean descending) { ... }
```

For a string input each example is the value as written; for any other type it is JSON (`"42"`, `"true"`, `"[\"a\",\"b\"]"`).

**Python**: the `training` decorator on the handler, `TrainingValues` in the parameter's `Annotated` metadata (or in `training(parameters={...})`). Values are Python values.

```python
@training(phrasings=["Show the rows of {file} where {where}"], default_phrasings="Read {file}",
          negatives=["How do I filter rows in pandas?"],
          prompts=[TrainingPrompt("Show me orders.csv", {"file": "orders.csv"})])
@server_tool(name="query", title="Query a CSV file", description="...")
def query(self, file: Annotated[str, Param(description="CSV file"), TrainingValues(examples=["orders.csv"])],
          descending: Annotated[bool, Param(description="Largest first"),
                                TrainingValues(phrasings={True: ["largest first"]})] = False) -> Rows: ...
```

**JSON** (`toolgate.json`): a `"training"` block on the tool and on each input. The JSON SDK compiles it into the Java annotations above, so the same checks apply.

```json
{"name": "server_greet", "kind": "SERVER", "...": "...",
 "inputs": {"text": {"type": "STRING", "description": "Who to greet", "maxLength": 128,
                     "training": {"examples": ["Grace", "the ops team"], "phrasings": {"World": ["everyone"]}}}},
 "training": {"phrasings": ["Greet {text} from the server"], "defaultPhrasings": ["Run the server greeting"],
              "negatives": ["What does hello world mean?"],
              "examples": [{"prompt": "Say hi to Lin", "arguments": {"text": "Lin"}}]}}
```

## 3. Model-validation prompts

Training hints are what a model learns from. **Model-validation prompts** are what it is tested on: requests written the way real users write them, kept apart so the test measures how the trained model copes with wording it never saw. A dataset builder writes them to its validation set and never into the training data.

| Field | Meaning |
|---|---|
| `prompts` | Requests that must call the tool, each with the exact arguments the call must carry: `{"prompt": "...", "arguments": {...}}`. The arguments are the expected answer, so they must be a complete, valid call: every required input, values of the right type and enum. |
| `negatives` | Requests that must not call the tool and are answered in plain text. No placeholders. |

**Java**: `@ModelValidation` on the handler, beside `@Training`, reusing `@TrainingPrompt`:

```java
@ServerTool(name = "get_forecast", title = "Forecast", description = "...")
@Training(phrasings = "Weather in {city}")
@ModelValidation(
        prompts = {@TrainingPrompt(prompt = "yo whats it like in Oslo tmrw", arguments = "{\"city\":\"Oslo\"}"),
                   @TrainingPrompt(prompt = "A week of weather for Rome", arguments = "{\"city\":\"Rome\",\"days\":7}")},
        negatives = "Why is the sky blue?")
public Forecast forecast(@Param(description = "City") String city, @Param(description = "Days", required = false) Integer days) { ... }
```

**Python**: the `model_validation` decorator: `@model_validation(prompts=[TrainingPrompt("yo whats it like in Oslo tmrw", {"city": "Oslo"})], negatives=["Why is the sky blue?"])`.

**JSON** (`toolgate.json`): a `"modelValidation"` block on the tool: `{"prompts": [{"prompt": "...", "arguments": {...}}], "negatives": ["..."]}`.

They are written to the same `training/annotations.json` entry under `modelValidation` (and so reach the tool schema's `annotations`):

```json
{"tools": {"get_forecast": {"phrasings": ["Weather in {city}"],
  "modelValidation": {"prompts": [{"prompt": "yo whats it like in Oslo tmrw", "arguments": {"city": "Oslo"}}],
                      "negatives": ["Why is the sky blue?"]}}}}
```

## 4. Build-time checks

The build fails when a hint does not fit the tool, so a renamed input or a changed type cannot leave stale training data:

- a `{placeholder}` in `phrasings` or `defaultPhrasings` names no input; `negatives` have no placeholders;
- training values or a prompt argument name an input the tool does not have;
- an example value does not suit its input's type or enum;
- a phrased value of a boolean is not `true`/`false`, or of an enum is not one of its constants;
- a list repeats an entry, an entry is blank or longer than 1024 characters, or a list has more than 200 entries;
- `@Training` / `training` is on something that is not a tool handler.

For model-validation prompts, also:

- a prompt's arguments name an input the tool does not have, leave out a required input, or have a value that does not suit its input;
- a prompt or negative is also one of the tool's training hints (a phrasing, default phrasing, negative or training prompt), so it would test what the model was trained on;
- the block declares neither prompts nor negatives, or `@ModelValidation` / `model_validation` is on something that is not a tool handler.

## 5. The generated file

The SDK build writes the staging directory's `training/annotations.json`, marked as generated:

```json
{"generatedBy": "toolgate-sdk-java",
 "tools": {"query": {"phrasings": ["..."], "defaultPhrasings": ["..."], "negatives": ["..."],
                     "parameters": {"file": {"examples": ["orders.csv"]}, "descending": {"phrasings": {"true": ["largest first"]}}},
                     "examples": [{"prompt": "...", "arguments": {"file": "orders.csv"}}]}}}
```

`toolgate pack` folds it into `<package>.tool-schema.json` under each tool's `annotations`, and writes it to `training/annotations.json` beside the `.tgpkg` (marked `"generatedBy": "toolgate pack"`), where the dataset builder looks for it. A later build replaces that file, or removes it when no tool declares hints any more. `pack` refuses an annotations file without the `generatedBy` mark, and a `training/annotations.json` in a tool project.
