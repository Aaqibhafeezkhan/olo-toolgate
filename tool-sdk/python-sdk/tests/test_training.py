# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""training and TrainingValues become the tools of training/annotations.json, checked against the inputs."""

import unittest

from support import tools


class TrainingTest(unittest.TestCase):
    def ok(self, r):
        self.assertTrue(r.success, r.errors)
        return r

    def fails(self, r, *fragments):
        self.assertFalse(r.success, "built")
        for f in fragments:
            self.assertIn(f, r.errors)

    def test_hints_are_collected_in_the_annotations_format(self):
        r = self.ok(tools("", '''
            @training(phrasings=["Show {file} sorted by {sort_by}", "Top {limit} rows of {file}"],
                      default_phrasings="Read {file}",
                      negatives=["How do I open a CSV in Excel?"],
                      parameters={"sort_by": TrainingValues(examples=["amount", "date"])},
                      prompts=[TrainingPrompt("Show orders.csv", {"file": "orders.csv"})])
            @server_tool(name="query", title="T", description="D")
            def run(self, file: Annotated[str, Param(description="File"), TrainingValues(examples=["orders.csv"])],
                    sort_by: Annotated[str, Param(description="Sort")],
                    limit: Annotated[int, Param(description="Limit"), TrainingValues(examples=[5, 10])] = 20,
                    descending: Annotated[bool, Param(description="Desc"),
                                          TrainingValues(phrasings={True: ["largest first"]})] = False) -> Out:
                return None
            '''))
        self.assertEqual({"query": {
            "phrasings": ["Show {file} sorted by {sort_by}", "Top {limit} rows of {file}"],
            "defaultPhrasings": ["Read {file}"],
            "negatives": ["How do I open a CSV in Excel?"],
            "parameters": {"file": {"examples": ["orders.csv"]}, "limit": {"examples": [5, 10]},
                           "descending": {"phrasings": {"true": ["largest first"]}},
                           "sort_by": {"examples": ["amount", "date"]}},
            "examples": [{"prompt": "Show orders.csv", "arguments": {"file": "orders.csv"}}],
        }}, r.build.training)
        self.assertNotIn(b"largest first", r.build.bytes["query"])

    def test_a_tool_without_hints_has_no_entry(self):
        r = self.ok(tools("", '''
            @server_tool(name="plain", title="T", description="D")
            def run(self, id: Annotated[str, Param(description="Id")]) -> Out:
                return None
            '''))
        self.assertEqual({}, r.build.training)

    def test_hints_are_checked_against_the_inputs(self):
        self.fails(tools("", '''
            @training(phrasings="Look up {city}")
            @server_tool(name="t", title="T", description="D")
            def run(self, id: Annotated[str, Param(description="Id")]) -> Out:
                return None
            '''), "names {city}, which is not an input")
        self.fails(tools("", '''
            @training(parameters={"city": TrainingValues(examples=["Paris"])})
            @server_tool(name="t", title="T", description="D")
            def run(self, id: Annotated[str, Param(description="Id")]) -> Out:
                return None
            '''), "city, which is not an input")
        self.fails(tools("", '''
            @server_tool(name="t", title="T", description="D")
            def run(self, n: Annotated[int, Param(description="N"), TrainingValues(examples=["ten"])]) -> Out:
                return None
            '''), "does not suit input n")
        self.fails(tools("", '''
            @server_tool(name="t", title="T", description="D")
            def run(self, b: Annotated[bool, Param(description="B"), TrainingValues(phrasings={"yes": "sure"})]) -> Out:
                return None
            '''), "a phrased value is True or False")
        self.fails(tools("", '''
            @training(negatives="What is {id}?")
            @server_tool(name="t", title="T", description="D")
            def run(self, id: Annotated[str, Param(description="Id")]) -> Out:
                return None
            '''), "negatives are said as written")
        self.fails(tools("", '''
            @training(prompts=[{"prompt": "Go", "arguments": {"other": 1}}])
            @server_tool(name="t", title="T", description="D")
            def run(self, id: Annotated[str, Param(description="Id")]) -> Out:
                return None
            '''), "passes other, which is not an input")
        self.fails(tools("", '''
            @training(phrasings="x")
            def helper(self) -> Out:
                return None

            @server_tool(name="t", title="T", description="D")
            def run(self, id: Annotated[str, Param(description="Id")]) -> Out:
                return None
            '''), "training is on a function that is not a server_tool")

    def test_validation_prompts_go_beside_the_hints(self):
        r = self.ok(tools("", '''
            @training(phrasings="Weather in {city}")
            @model_validation(prompts=[TrainingPrompt("yo whats it like in Oslo tmrw", {"city": "Oslo"}),
                                       {"prompt": "A week of weather for Rome", "arguments": {"city": "Rome", "days": 7}}],
                              negatives="Why is the sky blue?")
            @server_tool(name="forecast", title="T", description="D")
            def run(self, city: Annotated[str, Param(description="City")],
                    days: Annotated[int, Param(description="Days")] = 3) -> Out:
                return None
            '''))
        self.assertEqual({"forecast": {
            "phrasings": ["Weather in {city}"],
            "modelValidation": {
                "prompts": [{"prompt": "yo whats it like in Oslo tmrw", "arguments": {"city": "Oslo"}},
                            {"prompt": "A week of weather for Rome", "arguments": {"city": "Rome", "days": 7}}],
                "negatives": ["Why is the sky blue?"]},
        }}, r.build.training)
        self.assertNotIn(b"Oslo", r.build.bytes["forecast"])

    def test_validation_prompts_must_be_new_valid_calls(self):
        for decl, fragment in (
                ('@model_validation(prompts=[{"prompt": "Go", "arguments": {}}])', "leaves out id, which a call must pass"),
                ('@model_validation(prompts=[{"prompt": "Go", "arguments": {"id": 5}}])', "which does not suit the input"),
                ('@model_validation(prompts=[{"prompt": "Go", "arguments": {"id": "a", "x": 1}}])', "passes x, which is not an input"),
                ('@training(negatives="What is an id?")\n@model_validation(negatives="What is an id?")', "is also a training hint"),
                ("@model_validation()", "needs prompts or negatives")):
            self.fails(tools("", "\n" + "\n".join(" " * 12 + line for line in decl.split("\n")) + '''
            @server_tool(name="t", title="T", description="D")
            def run(self, id: Annotated[str, Param(description="Id")]) -> Out:
                return None
            '''), fragment)


if __name__ == "__main__":
    unittest.main()
