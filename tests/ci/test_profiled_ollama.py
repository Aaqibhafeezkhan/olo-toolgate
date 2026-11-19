# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Local model custody does not replace keys on restart or leak them in public trust."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]


class OllamaCustodyTests(unittest.TestCase):
    def test_source_qualified_model_is_reused_and_missing_model_is_prepared_in_docker(self):
        spec=importlib.util.spec_from_file_location('profiled_ollama',ROOT/'debug/profiled/ollama.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        calls=[]
        def existing(command,**kwargs):
            calls.append(command)
            return SimpleNamespace(stdout='NAME ID SIZE\n'+module.QLORA_MODEL+' fixture 1GB\n')
        compose=['docker','compose','-p','fixture']
        module.prepare_qlora(existing,ROOT,'fixture-models',compose)
        self.assertEqual([compose+['exec','-T','ollama','ollama','list']],calls)
        calls.clear()
        def missing(command,**kwargs):
            calls.append(command);return SimpleNamespace(stdout='NAME ID SIZE\n')
        module.prepare_qlora(missing,ROOT,'fixture-models',compose)
        self.assertEqual('build',calls[1][1])
        self.assertIn('type=volume,source=fixture-models,target=/models',calls[2])
        self.assertIn(module.QLORA_MODEL,calls[-1])
        self.assertNotIn('pull',calls[-1])

    def test_opt_in_preserves_other_provider_origins_and_monitoring_and_refuses_custom_ca(self):
        spec=importlib.util.spec_from_file_location('profiled_ollama',ROOT/'debug/profiled/ollama.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            here=Path(temporary)/'debug/profiled';here.mkdir(parents=True)
            settings=here/'installation.env'
            settings.write_text('# operator settings\nMONITORING_ENABLED=true\nTOOLGATE_WORKFLOW_MODEL_ORIGINS=https://other.example\n',encoding='utf-8')
            with patch.object(module,'__file__',str(here/'ollama.py')),patch.object(module.sys,'argv',['ollama.py','configure']),patch.object(module.sys,'path',list(module.sys.path)):
                module.main();first=settings.read_bytes();module.main()
                self.assertEqual(first,settings.read_bytes())
                self.assertIn(b'MONITORING_ENABLED=true',first)
                self.assertIn(b'https://other.example,https://ollama-provider:9443',first)
                settings.write_text('TOOLGATE_WORKFLOW_MODEL_CA_FILE=/operator/custom-ca.pem\n',encoding='utf-8');before=settings.read_bytes()
                with self.assertRaisesRegex(ValueError,'Custom model CA preserved'):module.main()
                self.assertEqual(before,settings.read_bytes())

    def test_restart_preserves_private_identity_and_combines_only_public_trust(self):
        from cryptography import x509
        spec=importlib.util.spec_from_file_location('profiled_ollama',ROOT/'debug/profiled/ollama.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            base=Path(temporary);(base/'custody').mkdir()
            def mapped(value):return base/str(value).lstrip('/')
            with patch.object(module,'Path',side_effect=mapped),patch.object(module.os,'chown',create=True):
                module.initialize()
                folder=base/'custody/ollama';before={p.name:p.read_bytes() for p in folder.iterdir()}
                module.initialize()
                self.assertEqual(before,{p.name:p.read_bytes() for p in folder.iterdir()})
                cert=x509.load_pem_x509_certificate(before['server.crt'])
                self.assertEqual(['ollama-provider'],cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value.get_values_for_type(x509.DNSName))
                self.assertIn(before['token'],before['authorization.conf'])
                bundle=(base/'custody/model-ca.pem').read_bytes()
                self.assertEqual(before['ca.crt'],bundle)
                self.assertNotIn(before['token'],bundle)
                self.assertNotIn(b'PRIVATE KEY',bundle)
                mock=base/'custody/provider';mock.mkdir();(mock/'ca.crt').write_bytes(before['ca.crt'])
                module.initialize()
                self.assertEqual(before,{p.name:p.read_bytes() for p in folder.iterdir()})
                self.assertEqual(before['ca.crt']+b'\n'+before['ca.crt'],(base/'custody/model-ca.pem').read_bytes())


if __name__=='__main__':unittest.main()
