# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Conformance: the route-analysis cases of http.md section 3, the declared-value checks and declared(context)."""

import textwrap
import unittest

from support import tools

from toolgate.accessors import attribute_name, collision

ROUTES = '''
http_route(id="items.list", destination="api", method=HttpMethod.GET, path="/items")
http_route(id="admin.get", destination="api", method=HttpMethod.GET, path="/admin")
'''


def handler(annotations, statement):
    """A server tool listing items.get and items.list, whose body is the statement."""
    body = textwrap.indent(textwrap.dedent(statement).strip(), " " * 4)
    return tools(ROUTES + textwrap.dedent(annotations), '''
@server_tool(name="items.get", title="T", description="D", routes=["items.get", "items.list"])
def run(self, id: Annotated[str, Param(description="Id")], *, context: ToolContext) -> Out:
%s
    return None
''' % body)


class AnalysisTest(unittest.TestCase):
    def ok(self, r):
        self.assertTrue(r.success, r.errors)
        return r

    def fails(self, r, *fragments):
        self.assertFalse(r.success, "built")
        for f in fragments:
            self.assertIn(f, r.errors)

    def test_a_literal_call_matching_one_listed_route_packages(self):
        self.ok(handler("", 'context.http.get("api", "/items/{id}").path_var("id", id).send(str)'))
        self.ok(handler("", 'context.http.get("api", "/items/{id:[0-9]+}").path_var("id", id).send(str)'))
        self.ok(handler("", 'http = context.http\nhttp.get("api", "/items").send(str)'))

    def test_a_concatenated_template_fails_at_the_call_site(self):
        r = handler("", 'context.http.get("api", "/items/" + id).send(str)')
        self.fails(r, "string literals")
        self.assertTrue(all(p.site is not None and p.site.line > 0 for p in r.failure.problems),
                        "the error names the call site")
        self.fails(handler("", 'context.http.get("api", f"/items/{id}").send(str)'), "string literals")

    def test_a_template_or_destination_from_a_global_fails(self):
        self.fails(handler('Global(name="Path", type=ValueType.STRING)',
                           'context.http.get("api", context.globals.get_str("Path")).send(str)'), "string literals")
        self.fails(handler('Global(name="Dest", type=ValueType.STRING)',
                           'context.http.get(context.globals.get_str("Dest"), "/items").send(str)'), "string literals")

    def test_a_template_matching_two_routes_fails_naming_both(self):
        self.fails(handler('http_route(id="items.also", destination="api", method=HttpMethod.GET,'
                           ' path="/items/{id:[a-z]+}")',
                           'context.http.get("api", "/items/{id}").send(str)'),
                   "more than one route", "items.get", "items.also")

    def test_a_route_the_tool_does_not_list_fails(self):
        self.fails(handler("", 'context.http.get("api", "/admin").send(str)'), "route admin.get", "does not list")
        self.fails(handler("", 'context.http.post("api", "/items").send(str)'), "matches no declared route",
                   "candidates")

    def test_calls_outside_a_handler_fail(self):
        self.fails(tools(ROUTES, '''
@server_tool(name="items.get", title="T", description="D", routes="items.get")
def run(self, *, context: ToolContext) -> Out:
    return None

@staticmethod
def helper(http: HttpClient):
    return http.get("api", "/items").send(str)
'''), "outside a tool handler")

    def test_reflective_access_to_the_network_api_fails(self):
        self.fails(handler("", 'getattr(context.http, "get")("api", "/items")'), "reflective access to HttpClient")
        self.fails(handler("", 'import toolgate\nc = toolgate.TcpConnector'), "reflective access to TcpConnector")
        self.fails(handler("", 'c = getattr(context, "h" + "ttp")'), "reflective access to ToolContext")

    def test_literal_value_names_must_be_declared_and_read_with_their_type(self):
        self.ok(handler('Global(name="Limit", type=ValueType.INT)',
                        'context.globals.get_int("Limit")\ncontext.globals.get_long("Limit")'))
        self.fails(handler('Global(name="Limit", type=ValueType.INT)', 'context.globals.get_int("Limt")'),
                   "declares no global Limt", "declared: Limit")
        self.fails(handler('Global(name="Limit", type=ValueType.INT)', 'context.globals.get_str("Limit")'),
                   "global Limit of tool items.get is INT; get_str does not read it")
        self.fails(handler('Secret(name="Token")', 'context.secrets.get_str("Tokn")'), "declares no secret Tokn")
        self.fails(handler('JwtClaim(name="tenant", claim="tid")', 'context.jwt.get_int("tenant")'), "claim tenant",
                   "is STRING")
        self.ok(handler('JwtClaim(name="tenant", claim="tid")', 'context.jwt.get_str("tenant")'))
        self.fails(handler("", 'context.get_str("nope")'), "declares no input nope")
        self.ok(handler("", 'context.get_str("id")'))
        # A name the build cannot see is left to the runtime.
        self.ok(handler("", 'n = "no" + "pe"\ncontext.globals.get_str(n)'))

    def test_literal_error_codes_must_be_declared(self):
        self.fails(handler("", 'raise ToolBusinessError("NOT_FOUND", "x")'),
                   "tool items.get raises undeclared error code NOT_FOUND")
        self.ok(tools(ROUTES, '''
@server_tool(name="items.get", title="T", description="D", errors=[ErrorDecl("NOT_FOUND", "x")])
def run(self, *, context: ToolContext) -> Out:
    raise ToolBusinessError("NOT_FOUND", "x")
'''))

    def test_code_the_analyser_does_not_check_builds_untouched(self):
        self.ok(handler("", '''
            d = {"a": 1}
            d.get("a", 2)
            xs = [len(x) for x in ["a"]]
            r = lambda: context.http.get("api", "/items").send(str)
            '''))

    def test_typed_accessors_read_each_declared_value_with_its_getter(self):
        r = self.ok(tools(ROUTES + '''
Global(name="Limit", type=ValueType.INT)
Global(name="default", type=ValueType.BOOLEAN)
Secret(name="API_KEY")
JwtClaim(name="tenant", claim="tid")
''', '''
@server_tool(name="items.get", title="T", description="D", routes="items.get")
def run(self, *, context: ToolContext) -> Out:
    v = declared(context)
    return Out(str(v.globals.limit) + v.secrets.api_key + v.jwt.tenant + str(v.globals.default))
'''))
        self.assertTrue(r.success)

    def test_accessor_names_are_python_identifiers(self):
        self.assertEqual("limit", attribute_name("Limit"))
        self.assertEqual("api_key", attribute_name("API_KEY"))
        self.assertEqual("max_items_per_page", attribute_name("maxItemsPerPage"))
        self.assertEqual("class_value", attribute_name("class"))
        self.assertEqual("globals_value", attribute_name("globals"))
        self.assertEqual("max_items and maxItems", collision({"max_items": "INT", "maxItems": "INT"}))
        self.fails(tools('Global(name="max_items", type=ValueType.INT)\nGlobal(name="maxItems", type=ValueType.INT)',
                         '''
@server_tool(name="items.get", title="T", description="D")
def run(self) -> Out:
    return None
'''), "global max_items and maxItems would have the same attribute")


if __name__ == "__main__":
    unittest.main()
