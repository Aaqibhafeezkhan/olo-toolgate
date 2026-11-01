# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Negative controls for source-bound actual Control/knowledge evidence."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location("knowledge_gate",ROOT/"tools/security/knowledge.py")
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)

class KnowledgeQualificationTest(unittest.TestCase):
    def fixture(self,root):
        for name in ("memory","rag"):
            folder=root/f"olo-{name}/build/install/olo-{name}/lib";folder.mkdir(parents=True)
            (folder/"application.jar").write_bytes(b"test application")
        targets={name:[dict(effect_id=name+"-effect",auth_nonce=name+"-nonce",authorization_generation=1,state="DONE",
            outcome="COMPLETED",result_digest="sha256:"+"a"*64,delivered=1)] for name in ("memory","rag")}
        control=[dict(effect_id=name+"-effect",auth_nonce=name+"-nonce",generation=1,state="COMPLETED",consumer="olo_"+name)
            for name in ("memory","rag")]
        report=dict(passed=True,knowledgeSourceSha256=gate.source_digest(root),
            installedArtifacts=gate.installed_artifacts(root),imageId="sha256:"+"b"*64,toolgateRevision="c"*40,
            serviceImageIds={name:"sha256:"+"d"*64 for name in ("memory","rag")},embeddingDigest="sha256:"+"e"*64,
            fencedEffects=dict(passed=True,targets=targets,control=control,
                summary={name:dict(completed=1,pendingDelivery=0) for name in ("memory","rag")}))
        path=root/"build/qualification/real-stack.json";path.parent.mkdir(parents=True)
        selected=dict(Id=report["imageId"],Config=dict(Labels={"org.opencontainers.image.revision":report["toolgateRevision"]}))
        return report,path,selected

    def test_full_stack_evidence_requires_actual_matching_records(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);report,path,image=self.fixture(root)
            path.write_text(json.dumps(report))
            with patch.object(gate,"stack_image",return_value=image):
                self.assertTrue(gate.verify_stack(root,"owned-image")["passed"])
                for name in ("missing-record","generation","unsettled","summary","old-report"):
                    changed=copy.deepcopy(report)
                    if name=="missing-record":changed["fencedEffects"]["control"].pop()
                    elif name=="generation":changed["fencedEffects"]["control"][0]["generation"]=2
                    elif name=="unsettled":changed["fencedEffects"]["control"][0]["state"]="CONSUMED"
                    elif name=="summary":changed["fencedEffects"]["summary"]["memory"]["completed"]=2
                    else:changed.pop("fencedEffects")
                    path.write_text(json.dumps(changed))
                    with self.assertRaises(ValueError,msg=name):gate.verify_stack(root,"owned-image")

    def test_changed_artifact_source_or_image_cannot_reuse_a_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);report,path,image=self.fixture(root);path.write_text(json.dumps(report))
            with patch.object(gate,"stack_image",return_value=image):
                code=root/"main.py";code.write_text("changed")
                with self.assertRaises(ValueError):gate.verify_stack(root,"owned-image")
                code.unlink()
                jar=root/"olo-memory/build/install/olo-memory/lib/application.jar";jar.write_bytes(b"changed")
                with self.assertRaises(ValueError):gate.verify_stack(root,"owned-image")
                jar.write_bytes(b"test application")
            changed=copy.deepcopy(image);changed["Id"]="sha256:"+"f"*64
            with patch.object(gate,"stack_image",return_value=changed),self.assertRaises(ValueError):
                gate.verify_stack(root,"owned-image")

    def test_source_directory_links_are_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/"source";root.mkdir();outside=Path(temp)/"outside";outside.mkdir()
            (outside/"code.py").write_text("outside");(root/"linked").symlink_to(outside,target_is_directory=True)
            with self.assertRaises(ValueError):gate.source_digest(root)

    def test_unowned_or_changed_runtime_images_are_refused(self):
        image=dict(Id="sha256:"+"a"*64,Config=dict(Labels={"io.olo.qualification.owner":"olo-knowledge",
            "org.opencontainers.image.revision":"b"*40}))
        with patch.object(gate.subprocess,"check_output",side_effect=[json.dumps([image]),"",""]),patch.object(gate.subprocess,"run"):
            self.assertEqual(image,gate.stack_image("owned"))
        with patch.object(gate.subprocess,"check_output",side_effect=[json.dumps([image]),"apps/control-plane/changed.java",""]),patch.object(gate.subprocess,"run"),self.assertRaises(ValueError):
            gate.stack_image("old-runtime")
        image["Config"]["Labels"].pop("io.olo.qualification.owner")
        with patch.object(gate.subprocess,"check_output",return_value=json.dumps([image])),self.assertRaises(ValueError):
            gate.stack_image("unowned")

if __name__=="__main__":unittest.main()
