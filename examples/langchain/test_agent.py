# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Exercise the real LangChain loop with a scripted model, without paid inference.

Run in the built agent image: docker compose run --rm -T --no-deps
  --entrypoint python -v ./test_agent.py:/app/test_agent.py:ro agent /app/test_agent.py
"""
import contextlib
import io
import json
import os
import unittest
from unittest.mock import patch

import httpx
from openrouter import OpenRouter

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, ChatResult

import agent


class Gateway:
    def __init__(self):
        self.files = {'support-tickets.json': '{"id":"DEMO-101"}'}
        self.calls = []
        self.success = True
        self.corrupt_write = False
        self.omit_tool = None

    def rpc(self, method, params=None):
        self.calls.append((method, params))
        if method == 'tools/list':
            tools = []
            for name in agent.TOOLS | {'admin.unrelated'}:
                if name == self.omit_tool:
                    continue
                properties = {}
                if name in {'examples.hotfolder/read_text', 'examples.hotfolder/write_text'}:
                    properties['path'] = {'type': 'string'}
                if name == 'examples.hotfolder/write_text':
                    properties['text'] = {'type': 'string'}
                tools.append(dict(name=name, description='Example ' + name, inputSchema={
                    'type': 'object', 'properties': properties,
                    'required': list(properties), 'additionalProperties': False}))
            return {'tools': tools}
        name, arguments = params['name'], params['arguments']
        if name == 'examples.hotfolder/list':
            result = {'paths': list(self.files)}
        elif name == 'examples.hotfolder/read_text':
            result = {'text': self.files[arguments['path']]}
        elif name == 'examples.hotfolder/write_text':
            if self.success:
                self.files[arguments['path']] = 'wrong' if self.corrupt_write else arguments['text']
            result = {'written': self.success}
        else:
            raise AssertionError('Unrelated tool must not execute')
        return {'structuredContent': result}


class ScriptedModel(BaseChatModel):
    responses: list[AIMessage]
    cursor: int = 0

    @property
    def _llm_type(self):
        return 'scripted-example-test'

    def bind_tools(self, tools, **kwargs):
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        reply = self.responses[self.cursor]
        self.cursor += 1
        return ChatResult(generations=[ChatGeneration(message=reply)])


def tool_message(name, arguments):
    return AIMessage(content='', tool_calls=[dict(name=name, args=arguments, id=name)])


def model():
    return ScriptedModel(responses=[
        tool_message('examples_hotfolder_list', {}),
        tool_message('examples_hotfolder_read_text', {'path': 'support-tickets.json'}),
        tool_message('examples_hotfolder_write_text', {'path': 'support-triage.md',
                                            'text': '# Fictional support triage\nDEMO-101: investigate.'}),
        tool_message('examples_hotfolder_read_text', {'path': 'support-triage.md'}),
        AIMessage(content='Report written and checked.'),
    ])


class AgentTests(unittest.TestCase):
    def test_real_langchain_loop_dispatches_through_gateway_and_verifies_report(self):
        gateway = Gateway()
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(agent.run(gateway, 'tickets', model()), 'support-triage.md')
        self.assertIn('Verified report', output.getvalue())
        self.assertEqual([params['name'] for method, params in gateway.calls if method == 'tools/call'],
                         ['examples.hotfolder/list', 'examples.hotfolder/read_text', 'examples.hotfolder/write_text',
                          'examples.hotfolder/read_text', 'examples.hotfolder/read_text'])

    def test_scope_rejects_unrelated_reads_and_writes_before_gateway(self):
        gateway = Gateway()
        tools = agent.tools_for(gateway, {'support-tickets.json'}, 'support-triage.md')
        self.assertEqual(set(tools), agent.TOOLS)
        self.assertIn('Read only', tools['examples.hotfolder/read_text'].invoke({'path': 'private.txt'}))
        self.assertIn('Write only', tools['examples.hotfolder/write_text'].invoke({'path': 'private.txt', 'text': 'overwrite'}))
        self.assertEqual(tools['examples.hotfolder/read_text'].args_schema['properties']['path']['enum'],
                         ['support-tickets.json'])
        self.assertEqual(tools['examples.hotfolder/write_text'].args_schema['properties']['path']['enum'],
                         ['support-triage.md'])
        self.assertEqual(len(gateway.calls), 1)

    def test_missing_current_grant_stops_before_model_or_effects(self):
        gateway = Gateway()
        gateway.omit_tool = 'examples.hotfolder/write_text'
        with self.assertRaisesRegex(ValueError, 'target must expose'):
            agent.run(gateway, 'tickets', model())
        self.assertEqual(len(gateway.calls), 1)

    def test_model_can_correct_a_rejected_path_without_unrelated_gateway_access(self):
        gateway = Gateway()
        selected = model()
        selected.responses.insert(0, tool_message('examples_hotfolder_read_text', {'path': 'private.txt'}))
        with contextlib.redirect_stdout(io.StringIO()):
            agent.run(gateway, 'tickets', selected)
        self.assertTrue(all(params['arguments'].get('path') != 'private.txt'
                            for method, params in gateway.calls if method == 'tools/call'))
        self.assertIn('support-triage.md', gateway.files)

    def test_fabricated_completion_is_rejected(self):
        gateway = Gateway()
        fake = ScriptedModel(responses=[AIMessage(content='I wrote the report.')])
        with self.assertRaisesRegex(ValueError, 'did not write'):
            agent.run(gateway, 'tickets', fake)
        self.assertNotIn('support-triage.md', gateway.files)

    def test_readback_mismatch_is_rejected(self):
        gateway = Gateway()
        gateway.corrupt_write = True
        with self.assertRaisesRegex(ValueError, 'did not match'):
            agent.run(gateway, 'tickets', model())

    def test_failed_native_write_is_not_recorded_as_success(self):
        gateway = Gateway()
        gateway.success = False
        writes = []
        tools = agent.tools_for(gateway, writes=writes)
        with self.assertRaisesRegex(ValueError, 'did not confirm'):
            tools['examples.hotfolder/write_text'].invoke({'path': 'report.md', 'text': 'data'})
        self.assertEqual(writes, [])

    def test_smoke_exercises_device_operations_without_model(self):
        gateway = Gateway()
        with contextlib.redirect_stdout(io.StringIO()) as output:
            agent.smoke(gateway)
        self.assertIn('No model was called', output.getvalue())
        self.assertIn('langchain-smoke.txt', gateway.files)


class OpenRouterTests(unittest.TestCase):
    def test_openai_key_is_not_used_for_openrouter(self):
        with patch.dict(os.environ, {'OPENAI_API_KEY': 'unrelated-provider-key'}, clear=True):
            with self.assertRaisesRegex(ValueError, 'Set OPENROUTER_API_KEY'):
                agent.create_model()

    def test_free_router_runs_real_sdk_tool_loop_with_mock_http(self):
        key = 'test-only-openrouter-credential'
        requests = []
        responses = model().responses

        def reply(request):
            self.assertEqual(str(request.url), 'https://openrouter.ai/api/v1/chat/completions')
            self.assertEqual(request.headers['Authorization'], 'Bearer ' + key)
            body = json.loads(request.content)
            self.assertEqual(body['model'], 'openrouter/free')
            self.assertTrue(body['provider']['require_parameters'])
            self.assertEqual({item['function']['name'] for item in body['tools']},
                             {name.replace('.', '_').replace('/', '_') for name in agent.TOOLS})
            message = responses[len(requests)]
            requests.append(body)
            calls = [{'id': call['id'], 'type': 'function', 'function': {
                'name': call['name'], 'arguments': json.dumps(call['args'])}}
                for call in message.tool_calls]
            return httpx.Response(200, json=dict(id='test-response', created=0,
                object='chat.completion', model='openrouter/free', system_fingerprint='test', choices=[dict(index=0,
                finish_reason='tool_calls' if calls else 'stop', message=dict(role='assistant',
                content=message.content, tool_calls=calls))],
                usage=dict(prompt_tokens=10, completion_tokens=10, total_tokens=20)))

        with patch.dict(os.environ, {'OPENROUTER_API_KEY': key}, clear=True):
            selected = agent.create_model()
        self.assertEqual(selected.request_timeout, 45000)
        self.assertEqual(selected.max_retries, 0)
        self.assertEqual(selected.max_tokens, 4096)
        with httpx.Client(transport=httpx.MockTransport(reply)) as client:
            selected.client = OpenRouter(api_key=key, server_url=selected.openrouter_api_base,
                                         client=client)
            gateway = Gateway()
            with contextlib.redirect_stdout(io.StringIO()):
                agent.run(gateway, 'tickets', selected)
        self.assertEqual(len(requests), 5)
        self.assertIn('support-triage.md', gateway.files)
        self.assertTrue(any(message['role'] == 'tool' for message in requests[-1]['messages']))


if __name__ == '__main__':
    unittest.main()
