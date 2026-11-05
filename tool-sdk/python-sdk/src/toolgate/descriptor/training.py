# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Training hints: ``training`` and ``TrainingValues`` become one tool's entry of ``training/annotations.json``.

``model_validation`` prompts go to the same entry under ``modelValidation``: test prompts, never trained on.

The format is the one ``toolgate pack`` folds into the tool schema (spec/authoring/training.md), the same the Java
processor writes. Every hint is checked against the tool's inputs, so a renamed input or a changed type fails the
build instead of leaving stale training data behind.
"""

from __future__ import annotations

import enum
import re
from typing import Any, Dict, List, Mapping, Optional, Sequence, Tuple

from ..declarations import TrainingValues, model_validation, training
from .problems import Problem, Site

#: Where the staging directory, and the folder holding the ``.tgpkg``, carry the hints.
FILE = "training/annotations.json"
PLACEHOLDER = re.compile(r"\{([^{}]*)\}")
MAX_ITEMS = 200
MAX_TEXT = 1024


def value_key(value: Any) -> str:
    """How a value is keyed in ``phrasings``: ``true``, the enum constant, else its text."""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, enum.Enum):
        return value.name
    return str(value)


def fits(value: Any, schema: Mapping[str, Any]) -> bool:
    """Whether an example value suits an input schema's type."""
    if "enum" in schema and value not in schema["enum"]:
        return False
    t = schema.get("type")
    if t == "string":
        return isinstance(value, str)
    if t == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if t == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if t == "boolean":
        return isinstance(value, bool)
    if t == "array":
        items = schema.get("items")
        return isinstance(value, (list, tuple)) and (not isinstance(items, dict) or all(fits(v, items) for v in value))
    if t == "object":
        return isinstance(value, dict)
    return True


def _text(value: Any, what: str, site: Site) -> None:
    if not isinstance(value, str) or not value.strip():
        raise Problem(site, what + " is blank or not text")
    if len(value) > MAX_TEXT:
        raise Problem(site, what + " is longer than %d characters" % MAX_TEXT)


def _plain(value: Any) -> Any:
    """An example value as JSON: enum members by name (as the input schema lists them), tuples as lists."""
    if isinstance(value, enum.Enum):
        return value.name
    if isinstance(value, (list, tuple)):
        return [_plain(v) for v in value]
    if isinstance(value, dict):
        return {k: _plain(v) for k, v in value.items()}
    return value


def _values(tool: str, name: str, values: Any, properties: Mapping[str, Any], site: Site) -> Dict[str, Any]:
    if not isinstance(values, TrainingValues):
        raise Problem(site, "training values for input %s must be TrainingValues(...)" % name)
    schema = properties.get(name)
    if not isinstance(schema, dict):
        raise Problem(site, "training values name %s, which is not an input of tool %s" % (name, tool))
    notes: Dict[str, Any] = {}
    if values.examples:
        if len(values.examples) > MAX_ITEMS:
            raise Problem(site, "input %s has more than %d examples" % (name, MAX_ITEMS))
        examples: List[Any] = []
        for example in values.examples:
            value = _plain(example)
            if not fits(value, schema):
                raise Problem(site, "example %r does not suit input %s (%s)" % (example, name, schema.get("type")))
            if value in examples:
                raise Problem(site, "input %s lists example %r twice" % (name, example))
            examples.append(value)
        notes["examples"] = examples
    if values.phrasings:
        phrasings: Dict[str, List[str]] = {}
        for value, words in values.phrasings.items():
            key = value_key(value)
            if schema.get("type") == "boolean" and key not in ("true", "false"):
                raise Problem(site, "input %s is a boolean, so a phrased value is True or False, not %r" % (name, value))
            if "enum" in schema and key not in [str(v) for v in schema["enum"]]:
                raise Problem(site, "input %s has no value %s; it allows %s" % (name, key, schema["enum"]))
            if not words:
                raise Problem(site, "value %s of input %s needs at least one phrasing" % (key, name))
            for w in words:
                _text(w, "a phrasing of input " + name, site)
            if key in phrasings:
                raise Problem(site, "value %s of input %s is phrased twice" % (key, name))
            phrasings[key] = list(words)
        notes["phrasings"] = phrasings
    if not notes:
        raise Problem(site, "training values for input %s need examples or phrasings" % name)
    return notes


def _texts(tool: str, key: str, values: Sequence[str], properties: Optional[Mapping[str, Any]], site: Site) -> List[str]:
    if len(values) > MAX_ITEMS:
        raise Problem(site, "tool %s has more than %d %s" % (tool, MAX_ITEMS, key))
    seen: List[str] = []
    for value in values:
        _text(value, "one of the %s of tool %s" % (key, tool), site)
        if value in seen:
            raise Problem(site, 'tool %s lists "%s" twice in %s' % (tool, value, key))
        seen.append(value)
        for placeholder in PLACEHOLDER.findall(value):
            if properties is None:
                raise Problem(site, 'negative "%s" of tool %s has a placeholder; negatives are said as written'
                              % (value, tool))
            if placeholder not in properties:
                raise Problem(site, '%s "%s" of tool %s names {%s}, which is not an input; inputs are %s'
                              % (key, value, tool, placeholder, sorted(properties)))
    return seen


def _validation(tool: str, v: model_validation, t: Optional[training], properties: Mapping[str, Any],
                required: Sequence[str]) -> Dict[str, Any]:
    """The ``modelValidation`` entry: complete, valid calls that repeat no training hint."""
    vsite = Site.of(v)
    if not v.prompts and not v.negatives:
        raise Problem(vsite, "model_validation of tool %s needs prompts or negatives" % tool)
    trained = set()
    if t is not None:
        trained.update(t.phrasings, t.default_phrasings, t.negatives, (p.prompt for p in t.prompts))
    entry: Dict[str, Any] = {}
    if v.prompts:
        if len(v.prompts) > MAX_ITEMS:
            raise Problem(vsite, "tool %s has more than %d validation prompts" % (tool, MAX_ITEMS))
        prompts: List[Dict[str, Any]] = []
        for p in v.prompts:
            what = 'validation prompt "%s" of tool %s' % (p.prompt, tool)
            _text(p.prompt, "a validation prompt of tool " + tool, vsite)
            if any(x["prompt"] == p.prompt for x in prompts):
                raise Problem(vsite, what + " is listed twice")
            if p.prompt in trained:
                raise Problem(vsite, what + " is also a training hint; a validation prompt must be new to the model")
            arguments = _plain(p.arguments)
            for key, value in arguments.items():
                schema = properties.get(key)
                if not isinstance(schema, dict):
                    raise Problem(vsite, "%s passes %s, which is not an input of tool %s" % (what, key, tool))
                if not fits(value, schema):
                    raise Problem(vsite, "%s passes %s = %r, which does not suit the input (%s)"
                                  % (what, key, value, schema.get("type")))
            for name in required:
                if name not in arguments:
                    raise Problem(vsite, "%s leaves out %s, which a call must pass" % (what, name))
            prompts.append({"prompt": p.prompt, "arguments": arguments})
        entry["prompts"] = prompts
    if v.negatives:
        entry["negatives"] = _texts(tool, "negatives", v.negatives, None, vsite)
        for negative in v.negatives:
            if negative in trained:
                raise Problem(vsite, 'validation negative "%s" of tool %s is also a training hint; it must be new '
                              "to the model" % (negative, tool))
    return entry


def hints(tool: str, attached: Sequence[Any], properties: Mapping[str, Any],
          parameter_values: Mapping[str, Tuple[TrainingValues, Site]], site: Site,
          required: Sequence[str] = ()) -> Optional[Dict[str, Any]]:
    """The annotations entry of one tool, or None when it declares no hints and no validation prompts."""
    validations = [x for x in attached if isinstance(x, model_validation)]
    if len(validations) > 1:
        raise Problem(Site.of(validations[1]), "tool %s has more than one model_validation declaration" % tool)
    declared = [x for x in attached if isinstance(x, training)]
    if len(declared) > 1:
        raise Problem(Site.of(declared[1]), "tool %s has more than one training declaration" % tool)
    t = declared[0] if declared else None
    parameters: Dict[str, Any] = {}
    for name, (values, psite) in parameter_values.items():
        parameters[name] = _values(tool, name, values, properties, psite)
    if t is not None:
        tsite = Site.of(t)
        for name, values in t.parameters.items():
            if name in parameters:
                raise Problem(tsite, "training values for input %s are declared twice" % name)
            parameters[name] = _values(tool, name, values, properties, tsite)
    if t is None and not parameters and not validations:
        return None
    entry: Dict[str, Any] = {}
    if t is not None:
        tsite = Site.of(t)
        for key, values, props in (("phrasings", t.phrasings, properties),
                                   ("defaultPhrasings", t.default_phrasings, properties),
                                   ("negatives", t.negatives, None)):
            if values:
                entry[key] = _texts(tool, key, values, props, tsite)
    if parameters:
        entry["parameters"] = parameters
    if t is not None and t.prompts:
        prompts = []
        for p in t.prompts:
            _text(p.prompt, "a prompt of tool " + tool, Site.of(t))
            for key in p.arguments:
                if key not in properties:
                    raise Problem(Site.of(t), 'prompt "%s" passes %s, which is not an input of tool %s'
                                  % (p.prompt, key, tool))
            prompts.append({"prompt": p.prompt, "arguments": _plain(p.arguments)})
        entry["examples"] = prompts
    if validations:
        entry["modelValidation"] = _validation(tool, validations[0], t, properties, required)
    return entry
