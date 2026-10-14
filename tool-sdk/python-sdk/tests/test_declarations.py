# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Conformance: the sdk-unit declarations cases and descriptors/generation-required for Python."""

import unittest

from support import compile_package, tools

TOOL = '''
@server_tool(name="%s", title="T", description="D")
def run(self, id: Annotated[str, Param(description="Id")]) -> Out:
    return None
'''


def package_with_id(package_id):
    return compile_package({"__init__": '''
        from dataclasses import dataclass
        from toolgate import *
        tool_package(id="%s", name="Demo", publisher="Acme")

        @dataclass
        class Out:
            value: str

        class Tools:
            @server_tool(name="items.list", title="T", description="D")
            def run(self) -> Out:
                return None
        ''' % package_id})


class DeclarationTest(unittest.TestCase):
    def ok(self, r):
        self.assertTrue(r.success, r.errors)
        return r

    def fails(self, r, *fragments):
        self.assertFalse(r.success, "built")
        for f in fragments:
            self.assertIn(f, r.errors)

    def test_generation_is_required_and_has_no_default(self):
        self.fails(tools("", TOOL % "items.list", generation=None), "descriptor_generation", "has no default")
        self.fails(tools("", TOOL % "items.list", generation=2), "is not supported")
        self.ok(tools("", TOOL % "items.list", generation=1))

    def test_identifiers_follow_their_grammars(self):
        self.fails(tools("", TOOL % "Items.list"), "tool name Items.list")
        self.fails(tools("", TOOL % "a.b.c.d"), "tool name a.b.c.d")
        self.ok(tools("", TOOL % "a.b.c"))
        self.fails(tools("", (TOOL % "items.list").replace('Param(description="Id")',
                                                           'Param(name="1id", description="x")')), "input name 1id")
        self.fails(tools('http_route(id="Bad", destination="api", method=HttpMethod.GET, path="/x")',
                         TOOL % "items.list"), "route id Bad")
        self.fails(tools("", '''
            @server_tool(name="items.list", title="T", description="D", errors=[ErrorDecl("AB", "x")])
            def run(self) -> Out:
                return None
            '''), "error code AB")
        self.fails(package_with_id("Acme.demo"), "package id Acme.demo")
        self.fails(package_with_id("acme"), "package id acme")

    def test_tool_id_is_at_most_128_characters(self):
        # "acme.demo/" is 10 characters, so a 118-character local name gives a 128-character toolId.
        seg = "a" * 38
        self.ok(tools("", TOOL % (seg + "." + seg + "." + "c" * 40)))
        self.fails(tools("", TOOL % (seg + "." + seg + "." + "c" * 41)), "longer than 128")

    def test_reserved_prefixes_are_rejected(self):
        self.fails(package_with_id("toolgate.demo"), "reserved prefix toolgate.")
        self.fails(package_with_id("hotfolder.demo"), "reserved prefix hotfolder.")
        self.fails(tools("", TOOL % "builtin.items"), "reserved prefix builtin.")

    def test_variable_names_are_unique_ignoring_case(self):
        self.fails(tools('Global(name="ID", type=ValueType.STRING)', TOOL % "items.list"), "input id", "global ID")
        self.fails(tools('Secret(name="Token")\nJwtClaim(name="token", claim="sub")', TOOL % "items.list"),
                   "claim token", "secret Token")

    def test_tool_level_declarations_never_override_scope_level_ones(self):
        self.fails(tools('Global(name="Limit", type=ValueType.INT)', '''
            @server_tool(name="items.list", title="T", description="D")
            @Global(name="Limit", type=ValueType.INT, default_value="5")
            def run(self) -> Out:
                return None
            '''), "cannot override", "global Limit")

    def test_tool_attributes_take_the_specified_defaults(self):
        r = self.ok(tools("", '''
            @server_tool(name="items.get", title="T", description="D", routes="items.get")
            def get(self) -> Out:
                return None

            @client_tool(name="items.local", title="T", description="D")
            def local(self) -> Out:
                return None
            '''))
        server = r.descriptor("items.get")
        client = r.descriptor("items.local")
        self.assertIn('"effect":{"destructive":false,"openWorld":true,"safety":"UNKNOWN"}', server)
        self.assertIn('"effect":{"destructive":false,"openWorld":false,"safety":"UNKNOWN"}', client)
        for d in (server, client):
            self.assertIn('"execution":{"async":false,"cancelIfAbandoned":false,"maxDurationMs":30000,'
                          '"stateless":false}', d)
            self.assertIn('"businessKey":{"namespace":"AGENT","required":false}', d)
        self.assertIn('"tiers":["OCI_SANDBOX"]', client)
        self.assertNotIn('"tiers"', server)
        self.assertIn('"permissions":["HTTP_ROUTE"]', server)
        self.assertIn('"destinations":[{"hostSetting":"ApiHost","id":"api","port":443,"spkiPins":[]}]', server)

    def test_non_idempotent_destructive_tools_require_a_business_key(self):
        r = self.ok(tools("", '''
            @server_tool(name="items.delete", title="T", description="D", effect=EffectSafety.NON_IDEMPOTENT,
                         destructive=True)
            def delete(self) -> Out:
                return None

            @server_tool(name="items.create", title="T", description="D", effect=EffectSafety.NON_IDEMPOTENT)
            def create(self) -> Out:
                return None

            @server_tool(name="items.purge", title="T", description="D", effect=EffectSafety.NON_IDEMPOTENT,
                         destructive=True, requires_business_key=False)
            def purge(self) -> Out:
                return None
            '''))
        self.assertIn('"businessKey":{"namespace":"AGENT","required":true}', r.descriptor("items.delete"))
        self.assertIn('"businessKey":{"namespace":"AGENT","required":false}', r.descriptor("items.create"))
        self.assertIn('"businessKey":{"namespace":"AGENT","required":false}', r.descriptor("items.purge"))

    def test_async_tools_default_to_900000_ms(self):
        r = self.ok(tools("", '''
            @server_tool(name="items.export", title="T", description="D", async_=True)
            def export(self) -> Out:
                return None
            '''))
        self.assertIn('"maxDurationMs":900000', r.descriptor("items.export"))
        self.fails(tools("", '''
            @server_tool(name="items.slow", title="T", description="D", max_duration_ms=120000)
            def slow(self) -> Out:
                return None
            '''), "make the tool async")

    def test_title_and_text_are_exclusive_aliases(self):
        r = self.ok(tools("", '''
            @server_tool(name="items.list", text="Listed", description="D")
            def run(self) -> Out:
                return None
            '''))
        self.assertIn('"title":"Listed"', r.descriptor("items.list"))
        self.assertNotIn('"text"', r.descriptor("items.list"))
        self.fails(tools("", '''
            @server_tool(name="items.list", title="A", text="B", description="D")
            def run(self) -> Out:
                return None
            '''), "exactly one of title and text")
        self.fails(tools("", '''
            @server_tool(name="items.list", description="D")
            def run(self) -> Out:
                return None
            '''), "exactly one of title and text")

    def test_client_tools_cannot_declare_egress_or_secrets(self):
        client = '''
            @client_tool(name="items.local", title="T", description="D"%s)
            def local(self) -> Out:
                return None
            '''
        # routes and tcp are not parameters of client_tool, so the import itself fails.
        r = tools("", client % ', routes="items.get"')
        self.assertFalse(r.success)
        self.assertIsInstance(r.failure, TypeError)
        r = tools("", client % ', tcp="db"')
        self.assertFalse(r.success)
        self.assertIsInstance(r.failure, TypeError)
        self.fails(tools('Secret(name="Token")', client % ""), "client tool cannot use secrets")
        self.fails(tools('http_auth(id="key", type=AuthType.API_KEY, header="X-Key")', client % ""),
                   "cannot be declared next to http_auth")

    def test_typed_and_grouped_forms_give_identical_bytes(self):
        typed = self.ok(tools("", '''
            @server_tool(name="items.find", title="T", description="D")
            def find(self, orderId: Annotated[str, Param(description="Order number", pattern="^[0-9]+$")],
                     count: Annotated[Optional[int], Param(description="How many", min="1", required=False)],
                     token: Annotated[str, Param(description="Token", sensitive=True)],
                     sort: Annotated[str, Param(description="Sort", enum_values=["ASC", "DESC"])] = "ASC",
                     *, context: ToolContext) -> Out:
                return None
            '''))
        grouped = self.ok(tools("", '''
            @server_tool(name="items.find", title="T", description="D")
            @tool_variables(inputs=[
                Variable(name="orderId", description="Order number", type=ValueType.STRING, pattern="^[0-9]+$"),
                Variable(name="count", description="How many", type=ValueType.INT, min="1", required=False),
                Variable(name="sort", description="Sort", type=ValueType.ENUM, enum_values=["ASC", "DESC"],
                         default_value="ASC"),
                Variable(name="token", description="Token", type=ValueType.STRING, sensitive=True),
            ])
            def find(self, *, context: ToolContext) -> Out:
                return None
            '''))
        self.assertEqual(typed.descriptor("items.find"), grouped.descriptor("items.find"))
        d = typed.descriptor("items.find")
        self.assertIn('"count":{"description":"How many","maximum":2147483647,"minimum":1,"type":"integer"}', d)
        self.assertIn('"required":["orderId","token"]', d)
        self.assertIn('"x-toolgate-sensitive":true', d)

    def test_objects_are_closed_unless_explicit_maps(self):
        r = self.ok(tools('''
            @dataclass
            class Address:
                street: str
                zip: Optional[str]
            ''', '''
            @server_tool(name="items.put", title="T", description="D")
            def put(self, address: Annotated[Address, Param(description="Where")],
                    counts: Annotated[Dict[str, int], Param(description="Counts")],
                    ids: Annotated[List[int], Param(description="Ids", type=ValueType.INFER)]) -> Out:
                return None
            '''))
        d = r.descriptor("items.put")
        self.assertIn('"address":{"additionalProperties":false,"description":"Where","properties":{"street":'
                      '{"type":"string"},"zip":{"type":"string"}},"required":["street"],"type":"object"}', d)
        self.assertIn('"counts":{"additionalProperties":{"maximum":2147483647,"minimum":-2147483648,'
                      '"type":"integer"},"description":"Counts","type":"object"}', d)
        self.assertIn('"ids":{"description":"Ids","items":{"maximum":2147483647,"minimum":-2147483648,'
                      '"type":"integer"},"type":"array"}', d)

    def test_recursive_types_use_defs(self):
        r = self.ok(tools('''
            @dataclass
            class Node:
                name: str
                children: List["Node"]
            ''', '''
            @server_tool(name="items.tree", title="T", description="D")
            def tree(self) -> Node:
                return None
            '''))
        self.assertIn('"outputSchema":{"$defs":{"Node":{"additionalProperties":false,"properties":{"children":'
                      '{"items":{"$ref":"#/$defs/Node"},"type":"array"},"name":{"type":"string"}},'
                      '"required":["children","name"],"type":"object"}},"$ref":"#/$defs/Node"}',
                      r.descriptor("items.tree"))

    def test_at_most_eight_examples(self):
        eight = "@example(arguments={})\n" * 8
        self.ok(tools("", eight + TOOL % "items.list"))
        self.fails(tools("", eight + "@example(arguments={})\n" + TOOL % "items.list"), "9 examples")

    def test_routes_are_checked(self):
        route = 'http_route(id="%s", destination="%s", method=HttpMethod.GET, path="%s"%s)'
        self.fails(tools(route % ("items.all", "api", "/items/{rest:.*}", ""), TOOL % "items.list"), "may not use .*")
        self.fails(tools(route % ("items.all", "api", "/items/{rest:.+}", ""), TOOL % "items.list"), "may not use .+")
        self.fails(tools(route % ("items.one", "api", "/items/{id}", ""), TOOL % "items.list"),
                   "path variable id needs a pattern")
        self.ok(tools(route % ("items.one", "api", "/items/{id:[0-9]+}", ""), TOOL % "items.list"))
        self.fails(tools(route % ("items.all", "api", "/items", ', headers="Cookie"'), TOOL % "items.list"),
                   "may not allow header Cookie")
        self.fails(tools(route % ("items.all", "api", "/items", ', headers="X-Forwarded-For"'), TOOL % "items.list"),
                   "may not allow header X-Forwarded-For")
        self.fails(tools(route % ("items.all", "nowhere", "/items", ""), TOOL % "items.list"),
                   "undeclared destination nowhere")
        self.fails(tools("", '''
            @server_tool(name="items.list", title="T", description="D", routes="items.missing")
            def run(self) -> Out:
                return None
            '''), "undeclared route items.missing")

    def test_permissions_are_derived(self):
        r = self.ok(tools('''
            http_auth(id="exchange", type=AuthType.TOKEN_EXCHANGE, audience="api")
            http_route(id="items.mine", destination="api", method=HttpMethod.GET, path="/me", auth="exchange")
            tcp_destination(id="db", host_setting="DbHost", port=5432)
            Secret(name="Personal", scope=SecretScope.USER)
            ''', '''
            @server_tool(name="items.mine", title="T", description="D", routes="items.mine", tcp="db")
            def mine(self) -> Out:
                return None
            '''))
        self.assertIn('"permissions":["CREDENTIALS_IN_SANDBOX","HTTP_ROUTE","TCP","TOKEN_EXCHANGE","USER_SECRET"]',
                      r.descriptor("items.mine"))

    # Python-specific placement and shape rules.

    def test_handlers_are_module_functions_or_instance_methods(self):
        self.ok(compile_package({"__init__": '''
            from dataclasses import dataclass
            from typing import Annotated
            from toolgate import *
            tool_package(id="acme.demo", name="Demo", publisher="Acme")

            @dataclass
            class Out:
                value: str

            @server_tool(name="items.list", title="T", description="D")
            def run(id: Annotated[str, Param(description="Id")], *, context: ToolContext) -> Out:
                return Out(id)
            '''}))
        self.fails(tools("", '''
            @staticmethod
            @server_tool(name="items.list", title="T", description="D")
            def run() -> Out:
                return None
            '''), "instance method")
        self.fails(tools("", '''
            @server_tool(name="items.list", title="T", description="D")
            def run(self, context: ToolContext) -> Out:
                return None
            '''), "must be keyword-only")
        self.fails(tools("", '''
            @server_tool(name="items.list", title="T", description="D")
            def run(self, id: str) -> Out:
                return None
            '''), "parameter id needs Param")
        self.fails(tools("", '''
            @server_tool(name="items.list", title="T", description="D")
            def run(self):
                return None
            '''), "annotate its return type")
        self.fails(tools('Global(name="X", type=ValueType.STRING)\nexample(arguments={})', TOOL % "items.list"),
                   "example decorates a tool handler")

    def test_python_defaults_become_declared_defaults(self):
        r = self.ok(tools("", '''
            @server_tool(name="items.list", title="T", description="D")
            def run(self, limit: Annotated[int, Param(description="Limit")] = 5,
                    exact: Annotated[bool, Param(description="Exact")] = False) -> Out:
                return None
            '''))
        d = r.descriptor("items.list")
        self.assertIn('"limit":{"default":5,"description":"Limit"', d)
        self.assertIn('"exact":{"default":false,"description":"Exact","type":"boolean"}', d)
        self.assertIn('"required":[]', d)
        self.fails(tools("", '''
            @server_tool(name="items.list", title="T", description="D")
            def run(self, limit: Annotated[int, Param(description="Limit", default_value="4")] = 5) -> Out:
                return None
            '''), "one default in Python and another in Param")

    def test_constraint_types_are_checked(self):
        self.fails(tools("", '''
            @server_tool(name="items.list", title="T", description="D")
            def run(self, id: Annotated[str, Param(description="Id", min="1")]) -> Out:
                return None
            '''), "min and max apply to INT, LONG and NUMBER only")
        self.fails(tools("", '''
            @server_tool(name="items.list", title="T", description="D")
            def run(self, n: Annotated[int, Param(description="N", pattern="x")]) -> Out:
                return None
            '''), "apply to STRING and URI only")
        self.fails(tools("", '''
            @server_tool(name="items.list", title="T", description="D")
            def run(self, n: Annotated[int, Param(description="N", type=ValueType.STRING)]) -> Out:
                return None
            '''), "does not match the Python type")


if __name__ == "__main__":
    unittest.main()
