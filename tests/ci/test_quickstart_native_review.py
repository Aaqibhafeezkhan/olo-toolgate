# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Native fixture witnesses never reinterpret ambiguity or self-approve original work."""
import copy
import importlib.util
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location("quickstart_native_review",ROOT/"tools/quickstart/check.py")
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)


class NativeReviewTest(unittest.TestCase):
    def history(self):
        return dict(operation=dict(state="OUTCOME_UNKNOWN",revision=4,journalComplete=True,
            unresolvedEffectCount=0,unsettledAttemptCount=1),attempts=[dict(attemptId="attempt",
            state="SUCCEEDED",consumed=True,scope="LEGACY_UNKNOWN",manifestSealed=True,settled=False)],
            truncated=False)

    def test_missing_unresolved_failed_or_unsealed_attempts_cannot_be_reviewed(self):
        history=self.history()
        self.assertTrue(gate.owned_native_review_candidate(history))
        self.assertFalse(gate.owned_native_review_candidate({}))
        for field,value in [("unresolvedEffectCount",1),("unsettledAttemptCount",2),
                            ("journalComplete",False),("state","RUNNING")]:
            altered=copy.deepcopy(history);altered["operation"][field]=value
            self.assertFalse(gate.owned_native_review_candidate(altered))
        for field,value in [("state","FAILED"),("state","OUTCOME_UNKNOWN"),("manifestSealed",False),
                            ("consumed",False),("settled",True)]:
            altered=copy.deepcopy(history);altered["attempts"][0][field]=value
            self.assertFalse(gate.owned_native_review_candidate(altered))

    def exercise(self,requester=None,owner="admin",original_reviewers=()):
        calls=[];reads=[0]
        def api(path,token=None,body=None,method="GET"):
            calls.append((path,token,body,method))
            if path.endswith("/history"):
                reads[0]+=1
                history=self.history()
                if reads[0]>1:history["operation"].update(state="SUCCEEDED",finalized=True,grade="HUMAN_RECONCILED")
                return 200,history
            if path.endswith("/invocations/op"):
                return 200,dict(state="SUCCEEDED",revision=3,resultDigest="a"*64,
                    evaluation=dict(resources=[],context=dict(userId=requester,agentId="agent",chain=[])))
            if path.endswith("/agents/agent"):return 200,dict(ownerUserId=owner)
            if "/approvals/" in path:return 200,dict(reviews=[dict(reviewerUserId=v) for v in original_reviewers])
            if path.endswith("/reconciliation"):return 200,dict(id="review",revision=1)
            if path.endswith("/decision"):return 200,dict(state="APPLIED")
            raise AssertionError(path)
        def ok(response):self.assertEqual(200,response[0]);return response[1]
        result=gate.review_owned_native_attempt(api,ok,dict(admin="admin",**{"reviewer-1":"r1","reviewer-2":"r2"}),
            "op",{}, "sha256:image")
        return result,calls

    def test_two_independent_actors_use_the_real_revision_bound_review(self):
        result,calls=self.exercise()
        self.assertEqual(["reviewer-1","reviewer-2"],result["reviewers"])
        posts=[v for v in calls if v[3]=="POST"]
        self.assertEqual(["r1","r2"],[v[1] for v in posts])
        self.assertEqual(dict(expectedOutcomeRevision=4,attemptIds=["attempt"],noFurtherEffects=True),
            posts[0][2]["attemptSettlement"])

    def test_requester_agent_owner_and_original_reviewer_cannot_witness_again(self):
        for arguments in [dict(requester="reviewer-1"),dict(owner="reviewer-2"),
                          dict(original_reviewers=("reviewer-1",))]:
            result,calls=self.exercise(**arguments)
            self.assertIsNone(result)
            self.assertFalse(any(v[3]=="POST" for v in calls))


if __name__=="__main__":unittest.main()
