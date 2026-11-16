-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
-- Fresh breaking-build schema. No upgrades from previous stage databases.
SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;

CREATE FUNCTION public.consent_approval_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM control_effect_consents c JOIN control_tenant_authority t ON t.tenant_id=c.tenant_id
 WHERE c.tenant_id=NEW.tenant_id AND c.consent_id=NEW.consent_id AND t.authorization_epoch=NEW.authorization_epoch
 AND c.not_before<=NEW.approved_at AND c.expires_at>NEW.approved_at
 AND NOT EXISTS(SELECT 1 FROM control_effect_consent_revocations r WHERE r.tenant_id=c.tenant_id AND r.consent_id=c.consent_id)) THEN RAISE EXCEPTION 'effect consent scope or allowance refused'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.consent_approvals_capacity() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+0 WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.consent_revocations_capacity() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+0 WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.consent_slots_capacity() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+0 WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.consent_use_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended(NEW.tenant_id||'/consent/'||NEW.consent_id||'/'||NEW.slot_index,0));
 IF NOT EXISTS(SELECT 1 FROM control_effect_consent_slots s
 JOIN control_effect_consents c ON c.tenant_id=s.tenant_id AND c.consent_id=s.consent_id
 JOIN control_effect_consent_approvals a ON a.tenant_id=c.tenant_id AND a.consent_id=c.consent_id
 JOIN control_tenant_authority t ON t.tenant_id=c.tenant_id AND (a.authorization_epoch=t.authorization_epoch OR (a.authorization_epoch<t.authorization_epoch AND (SELECT COUNT(*) FROM control_kill_events k WHERE k.tenant_id=t.tenant_id AND k.authorization_epoch>a.authorization_epoch AND k.authorization_epoch<=t.authorization_epoch)=t.authorization_epoch-a.authorization_epoch))
 JOIN control_effects e ON e.tenant_id=c.tenant_id AND e.operation_id=c.operation_id AND e.route_id=s.route_id
 JOIN control_effect_generations g ON g.tenant_id=e.tenant_id AND g.effect_id=e.effect_id AND g.generation=e.current_generation
 WHERE s.tenant_id=NEW.tenant_id AND s.consent_id=NEW.consent_id AND s.slot_index=NEW.slot_index
 AND e.effect_id=NEW.effect_id AND e.request_digest=NEW.request_digest
 AND c.not_before<=NEW.reserved_at AND c.expires_at>NEW.reserved_at AND a.approved_at<=NEW.reserved_at
 AND g.state='AUTHORIZED' AND g.expires_at>NEW.reserved_at
 AND NOT EXISTS(SELECT 1 FROM control_effect_consent_revocations r WHERE r.tenant_id=c.tenant_id AND r.consent_id=c.consent_id)
 AND (SELECT count(*) FROM control_effect_consent_uses u WHERE u.tenant_id=s.tenant_id AND u.consent_id=s.consent_id AND u.slot_index=s.slot_index)<s.permitted_count) THEN RAISE EXCEPTION 'effect consent scope or allowance refused'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.consent_uses_capacity() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+0 WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.consents_capacity() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+octet_length(NEW.document) WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_attempt_binding_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=NEW.tenant_id AND n.nonce=NEW.attempt_id
 AND n.purpose<>'SERVICE' AND n.invocation_id=NEW.operation_id AND n.evaluation_digest=NEW.evaluation_digest AND n.authorization_epoch=NEW.authorization_epoch
 AND n.issued_at=NEW.issued_at AND n.expires_at=NEW.expires_at) THEN RAISE EXCEPTION 'attempt must bind its original permit'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_attempt_effect_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN PERFORM 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id
 AND o.operation_id=NEW.operation_id FOR UPDATE;
 IF NOT(COALESCE(EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=NEW.tenant_id AND g.operation_id=NEW.operation_id AND g.attempt_id=NEW.attempt_id AND g.effect_id=NEW.effect_id)
 AND NOT EXISTS(SELECT 1 FROM control_attempt_manifest_seals m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id) AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.operation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED'),false)) THEN RAISE EXCEPTION 'manifest membership requires open admitted generation'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_attempt_effects_capacity() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+length(NEW.effect_id)
 WHERE tenant_id=NEW.tenant_id; IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_attempt_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN RAISE EXCEPTION 'attempt journal is immutable'; END $$;

CREATE FUNCTION public.control_audit_chain_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE head_index bigint; head_digest text;
BEGIN
 -- ADR 061: one linear chain per tenant; appends serialize on this tenant lock across replicas.
 PERFORM pg_advisory_xact_lock(hashtextextended(NEW.tenant_id::text||'/audit-chain',0));
 SELECT a.chain_index,a.row_digest INTO head_index,head_digest FROM public.control_audit a
  WHERE a.tenant_id=NEW.tenant_id ORDER BY a.chain_index DESC LIMIT 1;
 IF NEW.chain_index<>COALESCE(head_index,0)+1 OR NEW.previous_digest::text<>COALESCE(head_digest,repeat('0',64)) THEN
  RAISE EXCEPTION 'audit chain link mismatch' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_audit_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN RAISE EXCEPTION 'audit chain is append-only'; END $$;

CREATE FUNCTION public.control_attempt_manifest_seal_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN PERFORM 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id
 AND o.operation_id=NEW.operation_id FOR UPDATE;
 IF NOT(COALESCE(EXISTS(SELECT 1 FROM control_operation_attempts a JOIN control_enterprise_invocations i ON i.tenant_id=a.tenant_id AND i.invocation_id=a.operation_id
 WHERE a.tenant_id=NEW.tenant_id AND a.operation_id=NEW.operation_id AND a.attempt_id=NEW.attempt_id
 AND NEW.created_at>=a.issued_at AND i.revision=NEW.invocation_revision
 AND (i.state NOT IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') OR COALESCE(i.document::jsonb->>'reservedNonce','')<>NEW.attempt_id))
 AND NEW.document::jsonb->>'format'='toolgate-attempt-manifest-v1'
 AND NEW.document::jsonb->>'operationId'=NEW.operation_id AND NEW.document::jsonb->>'attemptId'=NEW.attempt_id
 AND jsonb_typeof(NEW.document::jsonb->'effects')='array' AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.document::jsonb->'effects') j(value) WHERE jsonb_typeof(j.value)<>'string')
 AND NEW.effect_count=(SELECT count(*) FROM jsonb_array_elements_text(NEW.document::jsonb->'effects') j(value))
 AND NEW.effect_count=(SELECT count(*) FROM control_attempt_effects m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id)
 AND NOT EXISTS(SELECT 1 FROM control_attempt_effects m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id
 AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements_text(NEW.document::jsonb->'effects') j(value) WHERE j.value=m.effect_id)),false)) THEN RAISE EXCEPTION 'manifest sealing requires closed authority and exact membership'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_attempt_manifest_seals_capacity() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+octet_length(NEW.document)
 WHERE tenant_id=NEW.tenant_id; IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_attempt_reservation_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$ BEGIN IF NEW.purpose<>'SERVICE' AND (
 NOT EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.invocation_id)
 OR EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=NEW.tenant_id AND n.invocation_id=NEW.invocation_id AND n.purpose<>'SERVICE'
 AND ((n.consumed_at IS NULL AND n.expires_at>NEW.issued_at)
 OR NOT EXISTS(SELECT 1 FROM control_operation_attempts a WHERE a.tenant_id=n.tenant_id AND a.attempt_id=n.nonce)
 OR (n.consumed_at IS NOT NULL AND NOT(EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i
 ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id WHERE r.tenant_id=n.tenant_id AND r.operation_id=n.invocation_id
 AND r.proof IN('QUALIFIED_BROKER_NO_WRITE','TARGET_DEDUPLICATION') AND r.created_at<=NEW.issued_at AND r.to_revision<=i.revision AND EXISTS(SELECT 1 FROM jsonb_array_elements_text(r.attempt_ids::jsonb) AS j(value) WHERE j.value=n.nonce))))))
 OR EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.invocation_id AND o.state='FAILED')) THEN RAISE EXCEPTION 'predecessor requires positive recovery coverage'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_attempt_review_capacity() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+octet_length(NEW.document) WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_attempt_review_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 PERFORM 1 FROM control_operations WHERE tenant_id=NEW.tenant_id AND operation_id=NEW.operation_id FOR UPDATE;
 IF NOT(EXISTS(SELECT 1 FROM control_attempt_manifest_seals m
 JOIN control_operation_attempts a ON a.tenant_id=m.tenant_id AND a.operation_id=m.operation_id AND a.attempt_id=m.attempt_id
 JOIN control_enterprise_nonces n ON n.tenant_id=a.tenant_id AND n.nonce=a.attempt_id
 JOIN control_enterprise_invocations i ON i.tenant_id=a.tenant_id AND i.invocation_id=a.operation_id
 JOIN control_operations o ON o.tenant_id=i.tenant_id AND o.operation_id=i.invocation_id
 JOIN control_operation_outcomes v ON v.tenant_id=i.tenant_id AND v.operation_id=i.invocation_id AND v.revision=NEW.expected_outcome_revision
 JOIN control_outcome_reconciliations c ON c.tenant_id=i.tenant_id AND c.reconciliation_id=NEW.reconciliation_id
 JOIN control_tenant_authority t ON t.tenant_id=i.tenant_id
 WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id
 AND NOT o.legacy AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND n.consumed_at<=NEW.recorded_at
 AND i.state NOT IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING')
 AND v.state='OUTCOME_UNKNOWN' AND v.invocation_revision=i.revision
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)
 AND v.document::jsonb->>'journalComplete'='true'
 AND (v.document::jsonb->>'unresolvedEffectCount')::bigint=0
 AND c.document::jsonb->>'state'='APPLIED'
 AND c.document::jsonb->>'invocationId'=i.invocation_id
 AND (c.document::jsonb->'request'->>'expectedRevision')::bigint=i.revision
 AND (c.document::jsonb->'request'->'attemptSettlement'->>'expectedOutcomeRevision')::bigint=NEW.expected_outcome_revision
 AND c.document::jsonb->'request'->'attemptSettlement'->>'noFurtherEffects'='true'

 AND EXISTS(SELECT 1 FROM control_records u WHERE u.tenant_id=i.tenant_id AND u.kind='USER' AND u.record_id=c.document::jsonb->>'requesterUserId' AND u.document::jsonb->>'enabled'='true')
 AND EXISTS(SELECT 1 FROM control_records u WHERE u.tenant_id=i.tenant_id AND u.kind='USER' AND u.record_id=c.document::jsonb->>'reviewerUserId' AND u.document::jsonb->>'enabled'='true')
 AND NOT EXISTS(SELECT 1 FROM control_records d WHERE d.tenant_id=i.tenant_id AND d.kind='AGENT' AND d.record_id IN(SELECT i.document::jsonb->'evaluation'->'context'->>'agentId' UNION SELECT h.value->>'agentId' FROM jsonb_array_elements(i.document::jsonb->'evaluation'->'context'->'chain') h) AND d.document::jsonb->>'ownerUserId' IN(c.document::jsonb->>'requesterUserId',c.document::jsonb->>'reviewerUserId'))
 AND NEW.attempt_id IN(SELECT value FROM jsonb_array_elements_text(c.document::jsonb->'request'->'attemptSettlement'->'attemptIds'))
 AND c.document::jsonb->>'requesterUserId'<>c.document::jsonb->>'reviewerUserId'
 AND (EXISTS(SELECT 1 FROM control_operation_review_owners w WHERE w.tenant_id=i.tenant_id AND w.operation_id=i.invocation_id
 AND c.document::jsonb->>'requesterUserId' NOT IN(SELECT value FROM jsonb_array_elements_text(w.document::jsonb->'owners')) AND c.document::jsonb->>'reviewerUserId' NOT IN(SELECT value FROM jsonb_array_elements_text(w.document::jsonb->'owners')))
 OR (NOT EXISTS(SELECT 1 FROM control_operation_review_owners w WHERE w.tenant_id=i.tenant_id AND w.operation_id=i.invocation_id)
 AND i.document::jsonb->'evaluation'->'context'->>'mode'='HUMAN' AND i.document::jsonb->'evaluation'->'context'->>'agentId' IS NULL AND jsonb_array_length(i.document::jsonb->'evaluation'->'context'->'chain')=0
 AND c.document::jsonb->>'requesterUserId'<>i.document::jsonb->'evaluation'->'context'->>'userId' AND c.document::jsonb->>'reviewerUserId'<>i.document::jsonb->'evaluation'->'context'->>'userId'))
 AND NOT EXISTS(SELECT 1 FROM control_execution_leases l WHERE l.tenant_id=i.tenant_id AND l.invocation_id=i.invocation_id AND l.fencing_token=m.attempt_id AND l.expires_at>NEW.recorded_at)
 AND NOT EXISTS(SELECT 1 FROM control_attempt_settlements s WHERE s.tenant_id=m.tenant_id AND s.operation_id=m.operation_id AND s.attempt_id=m.attempt_id)
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=m.tenant_id AND g.operation_id=m.operation_id AND g.attempt_id=m.attempt_id
 AND ((g.state NOT IN('RELEASED','COMPLETED','FAILED') AND NOT(g.state='AMBIGUOUS' AND EXISTS(SELECT 1 FROM control_effect_target_bindings b
 JOIN control_effect_intents intent ON intent.tenant_id=b.tenant_id AND intent.effect_id=b.effect_id AND intent.profile_digest=b.profile_digest
 JOIN control_effect_evidence witness ON witness.tenant_id=b.tenant_id AND witness.effect_id=b.effect_id
 WHERE b.tenant_id=g.tenant_id AND b.effect_id=g.effect_id AND witness.generation=g.generation
 AND b.document::jsonb->>'statusRouteId' IS NOT NULL AND substr(witness.source,1,14)='TARGET_LOOKUP:' AND length(witness.source)=78
 AND NOT EXISTS(SELECT 1 FROM control_effect_evidence other WHERE other.tenant_id=witness.tenant_id AND other.effect_id=witness.effect_id
 AND other.generation=witness.generation AND substr(other.source,1,14)='TARGET_LOOKUP:'
 AND (other.source<>witness.source OR other.outcome<>witness.outcome OR other.result_digest<>witness.result_digest))))) OR (g.state IN('COMPLETED','FAILED') AND (g.consumer IS NULL OR g.claim_token IS NULL OR NOT EXISTS(
 SELECT 1 FROM control_effect_evidence e WHERE e.tenant_id=g.tenant_id AND e.effect_id=g.effect_id AND e.generation=g.generation AND e.source='TARGET:'||g.consumer AND e.outcome=g.state)))))
 AND NEW.document::jsonb->>'format'='toolgate-attempt-review-v1' AND NEW.document::jsonb->>'operationId'=NEW.operation_id
 AND NEW.document::jsonb->>'attemptId'=NEW.attempt_id AND NEW.document::jsonb->>'manifestDigest'=m.manifest_digest
 AND NEW.document::jsonb->>'outcomeSourceDigest'=v.source_digest
 AND NEW.document::jsonb->'review'->>'requestDigest'=c.document::jsonb->>'requestDigest'
 AND (NEW.document::jsonb->>'directoryRevision')::bigint=t.revision AND (NEW.document::jsonb->>'authorizationEpoch')::bigint=t.authorization_epoch
)) THEN RAISE EXCEPTION 'attempt review requires exact independent approved closed resolved evidence'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_attempt_settlement_capacity() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
 BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+octet_length(NEW.document)
 WHERE tenant_id=NEW.tenant_id; IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_attempt_settlement_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN PERFORM 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.operation_id FOR UPDATE;
 IF NOT(COALESCE(EXISTS(SELECT 1 FROM control_attempt_manifest_seals m
 JOIN control_operation_attempts a ON a.tenant_id=m.tenant_id AND a.operation_id=m.operation_id AND a.attempt_id=m.attempt_id
 JOIN control_enterprise_nonces n ON n.tenant_id=a.tenant_id AND n.nonce=a.attempt_id
 JOIN control_enterprise_invocations i ON i.tenant_id=a.tenant_id AND i.invocation_id=a.operation_id
 WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id
 AND NEW.recorded_at>=m.created_at AND i.revision=NEW.invocation_revision AND n.purpose='EXECUTION'
 AND (i.state NOT IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') OR COALESCE(i.document::jsonb->>'reservedNonce','')<>NEW.attempt_id)
 AND NEW.document::jsonb->>'format'='toolgate-attempt-settlement-v1'
 AND NEW.document::jsonb->>'operationId'=NEW.operation_id AND NEW.document::jsonb->>'attemptId'=NEW.attempt_id
 AND NEW.document::jsonb->>'proof'=NEW.proof AND NEW.document::jsonb->>'manifestDigest'=m.manifest_digest
 AND NOT EXISTS(SELECT 1 FROM control_execution_leases l WHERE l.tenant_id=m.tenant_id AND l.invocation_id=m.operation_id AND l.fencing_token=m.attempt_id AND l.expires_at>NEW.recorded_at)
 AND (SELECT count(*) FROM control_effect_generations g WHERE g.tenant_id=m.tenant_id AND g.operation_id=m.operation_id AND g.attempt_id=m.attempt_id)<=4096
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=m.tenant_id AND g.operation_id=m.operation_id AND g.attempt_id=m.attempt_id
 AND ((g.state NOT IN('RELEASED','COMPLETED','FAILED') AND NOT(g.state='AMBIGUOUS' AND EXISTS(SELECT 1 FROM control_effect_target_bindings b
 JOIN control_effect_intents intent ON intent.tenant_id=b.tenant_id AND intent.effect_id=b.effect_id AND intent.profile_digest=b.profile_digest
 JOIN control_effect_evidence witness ON witness.tenant_id=b.tenant_id AND witness.effect_id=b.effect_id
 WHERE b.tenant_id=g.tenant_id AND b.effect_id=g.effect_id AND witness.generation=g.generation
 AND b.document::jsonb->>'statusRouteId' IS NOT NULL AND substr(witness.source,1,14)='TARGET_LOOKUP:' AND length(witness.source)=78
 AND NOT EXISTS(SELECT 1 FROM control_effect_evidence other WHERE other.tenant_id=witness.tenant_id AND other.effect_id=witness.effect_id
 AND other.generation=witness.generation AND substr(other.source,1,14)='TARGET_LOOKUP:'
 AND (other.source<>witness.source OR other.outcome<>witness.outcome OR other.result_digest<>witness.result_digest)))))
 OR (g.state IN('COMPLETED','FAILED') AND (g.consumer IS NULL OR g.claim_token IS NULL OR NOT EXISTS(
 SELECT 1 FROM control_effect_evidence e WHERE e.tenant_id=g.tenant_id AND e.effect_id=g.effect_id AND e.generation=g.generation
 AND e.source='TARGET:'||g.consumer AND e.outcome=g.state)))
 OR NOT EXISTS(SELECT 1 FROM control_attempt_effects x WHERE x.tenant_id=g.tenant_id AND x.operation_id=g.operation_id AND x.attempt_id=g.attempt_id AND x.effect_id=g.effect_id)))
 AND ((NEW.proof='NEVER_CONSUMED' AND n.consumed_at IS NULL AND n.expires_at<=NEW.recorded_at
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=m.tenant_id AND g.operation_id=m.operation_id AND g.attempt_id=m.attempt_id))
 OR (NEW.proof='QUALIFIED_BROKER_ONLY' AND n.consumed_at IS NOT NULL AND EXISTS(
 SELECT 1 FROM control_attempt_recovery_scopes s JOIN control_endpoints ep ON ep.tenant_id=s.tenant_id AND ep.device_id=s.device_id
 JOIN control_records d ON d.tenant_id=s.tenant_id AND d.kind='DEVICE' AND d.record_id=s.device_id
 WHERE s.tenant_id=m.tenant_id AND s.operation_id=m.operation_id AND s.attempt_id=m.attempt_id AND s.device_id=i.document::jsonb->'evaluation'->'context'->>'deviceId'
 AND ep.document::jsonb->>'state'='ACTIVE' AND d.document::jsonb->>'enabled'='true' FOR SHARE OF ep,d)))),false)) THEN RAISE EXCEPTION 'attempt settlement requires closed resolved witnessed authority'; END IF;
 RETURN NEW; END $$;



CREATE FUNCTION public.control_builder_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.kind='TEST' AND (OLD.document::jsonb->>'expiresAtUnixMs')::bigint <
    (extract(epoch from clock_timestamp())*1000)::bigint-604800000 THEN RETURN OLD; END IF;
  RAISE EXCEPTION 'authoring retention violation';
 END IF;
 IF OLD.kind='VERSION' OR (OLD.kind='DRAFT' AND (OLD.document::jsonb->>'sealed')::boolean)
  OR NEW.tenant_id<>OLD.tenant_id OR NEW.kind<>OLD.kind OR NEW.record_id<>OLD.record_id
  OR NEW.revision<>OLD.revision+1 THEN RAISE EXCEPTION 'authoring immutable identity violation'; END IF;
 RETURN NEW;
END;
$$;

CREATE FUNCTION public.control_business_key_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NEW.tenant_id IS NOT DISTINCT FROM OLD.tenant_id AND NEW.tool_id IS NOT DISTINCT FROM OLD.tool_id AND NEW.namespace IS NOT DISTINCT FROM OLD.namespace AND NEW.namespace_identity IS NOT DISTINCT FROM OLD.namespace_identity AND NEW.business_key_hash IS NOT DISTINCT FROM OLD.business_key_hash AND NEW.source IS NOT DISTINCT FROM OLD.source AND NEW.fingerprint IS NOT DISTINCT FROM OLD.fingerprint AND NEW.caller_agent_id IS NOT DISTINCT FROM OLD.caller_agent_id AND NEW.caller_user_id IS NOT DISTINCT FROM OLD.caller_user_id AND NEW.invocation_id IS NOT DISTINCT FROM OLD.invocation_id AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at AND NEW.dedupe_expires_at IS NOT DISTINCT FROM OLD.dedupe_expires_at AND NEW.retain_until IS NOT DISTINCT FROM OLD.retain_until AND NEW.tombstoned_at IS NOT DISTINCT FROM OLD.tombstoned_at AND EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=OLD.tenant_id AND v.operation_id=OLD.invocation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)
 AND NEW.outcome IS NOT DISTINCT FROM CASE v.state WHEN 'RUNNING' THEN NULL WHEN 'SUCCEEDED' THEN 'COMPLETED'
 WHEN 'FAILED' THEN 'FAILED' WHEN 'PARTIAL' THEN 'PARTIAL' ELSE 'OUTCOME_UNKNOWN' END) THEN RETURN NEW; END IF;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'business keys are retained'; END IF;
 IF NEW.tenant_id IS NOT DISTINCT FROM OLD.tenant_id AND NEW.tool_id IS NOT DISTINCT FROM OLD.tool_id AND NEW.namespace IS NOT DISTINCT FROM OLD.namespace AND NEW.namespace_identity IS NOT DISTINCT FROM OLD.namespace_identity AND NEW.business_key_hash IS NOT DISTINCT FROM OLD.business_key_hash AND NEW.source IS NOT DISTINCT FROM OLD.source AND NEW.fingerprint IS NOT DISTINCT FROM OLD.fingerprint AND NEW.caller_agent_id IS NOT DISTINCT FROM OLD.caller_agent_id AND NEW.caller_user_id IS NOT DISTINCT FROM OLD.caller_user_id AND NEW.invocation_id IS NOT DISTINCT FROM OLD.invocation_id AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at AND NEW.dedupe_expires_at IS NOT DISTINCT FROM OLD.dedupe_expires_at AND NEW.retain_until IS NOT DISTINCT FROM OLD.retain_until AND NEW.tombstoned_at IS NOT DISTINCT FROM OLD.tombstoned_at
 AND NEW.outcome IS NULL AND OLD.outcome IN('FAILED','EXPIRED','OUTCOME_UNKNOWN')
 AND OLD.tombstoned_at IS NULL
 AND EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id
 JOIN control_operations o ON o.tenant_id=i.tenant_id AND o.operation_id=i.invocation_id
 WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.invocation_id AND r.to_revision=i.revision AND i.state='QUEUED' AND o.state='RUNNING'
 AND OLD.retain_until>r.created_at) THEN RETURN NEW; END IF;
 IF (NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.tool_id IS DISTINCT FROM OLD.tool_id OR NEW.namespace IS DISTINCT FROM OLD.namespace OR NEW.namespace_identity IS DISTINCT FROM OLD.namespace_identity OR NEW.business_key_hash IS DISTINCT FROM OLD.business_key_hash) OR NEW.source<>OLD.source OR
 (NEW.invocation_id<>OLD.invocation_id AND OLD.source<>'DEDUPE_WINDOW' AND
 (OLD.tombstoned_at IS NOT NULL OR (NEW.fingerprint IS DISTINCT FROM OLD.fingerprint OR NEW.caller_agent_id IS DISTINCT FROM OLD.caller_agent_id OR NEW.caller_user_id IS DISTINCT FROM OLD.caller_user_id OR NEW.dedupe_expires_at IS DISTINCT FROM OLD.dedupe_expires_at OR NEW.retain_until IS DISTINCT FROM OLD.retain_until) OR
 ((NEW.invocation_id<>OLD.invocation_id OR NEW.created_at<>OLD.created_at)
 AND NOT(COALESCE(OLD.outcome,'') IN('EXPIRED','FAILED') AND NEW.outcome IS NULL)))) OR
 (NEW.invocation_id<>OLD.invocation_id AND OLD.source='DEDUPE_WINDOW' AND
 (OLD.dedupe_expires_at>NEW.created_at OR NEW.fingerprint IS DISTINCT FROM OLD.fingerprint OR NEW.caller_agent_id IS DISTINCT FROM OLD.caller_agent_id OR NEW.caller_user_id IS DISTINCT FROM OLD.caller_user_id OR NEW.outcome IS NOT NULL
 OR NEW.tombstoned_at IS NOT NULL OR NEW.retain_until<NEW.dedupe_expires_at
 OR NOT EXISTS(SELECT 1 FROM control_business_key_history h JOIN control_operations o
 ON o.tenant_id=h.tenant_id AND o.operation_id=h.invocation_id
 WHERE h.tenant_id IS NOT DISTINCT FROM OLD.tenant_id AND h.tool_id IS NOT DISTINCT FROM OLD.tool_id AND h.namespace IS NOT DISTINCT FROM OLD.namespace AND h.namespace_identity IS NOT DISTINCT FROM OLD.namespace_identity AND h.business_key_hash IS NOT DISTINCT FROM OLD.business_key_hash AND h.invocation_id IS NOT DISTINCT FROM OLD.invocation_id AND h.fingerprint IS NOT DISTINCT FROM OLD.fingerprint AND h.caller_agent_id IS NOT DISTINCT FROM OLD.caller_agent_id AND h.caller_user_id IS NOT DISTINCT FROM OLD.caller_user_id AND h.created_at IS NOT DISTINCT FROM OLD.created_at AND h.dedupe_expires_at IS NOT DISTINCT FROM OLD.dedupe_expires_at AND h.retain_until IS NOT DISTINCT FROM OLD.retain_until AND h.outcome IS NOT DISTINCT FROM OLD.outcome AND h.tombstoned_at IS NOT DISTINCT FROM OLD.tombstoned_at AND h.archived_at=NEW.created_at AND NOT o.legacy
 AND ((o.state IN('FAILED','SUCCEEDED') OR EXISTS(SELECT 1 FROM control_operation_outcomes v
 WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id AND v.state IN('SUCCEEDED','FAILED')
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))
 AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id
 AND v.state='RECONCILIATION_REQUIRED' AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))))) OR (NEW.invocation_id=OLD.invocation_id AND
 (OLD.tombstoned_at IS NOT NULL OR (NEW.fingerprint IS DISTINCT FROM OLD.fingerprint OR NEW.caller_agent_id IS DISTINCT FROM OLD.caller_agent_id OR NEW.caller_user_id IS DISTINCT FROM OLD.caller_user_id OR NEW.dedupe_expires_at IS DISTINCT FROM OLD.dedupe_expires_at OR NEW.retain_until IS DISTINCT FROM OLD.retain_until) OR NEW.created_at<>OLD.created_at
 OR (NEW.outcome IS DISTINCT FROM OLD.outcome AND OLD.outcome IS NOT NULL
 AND NOT(OLD.outcome='OUTCOME_UNKNOWN' AND NEW.outcome IN('COMPLETED','FAILED'))) OR (NEW.tombstoned_at IS DISTINCT FROM OLD.tombstoned_at AND
 (NEW.tombstoned_at IS NULL OR NEW.tombstoned_at<OLD.retain_until OR
 OLD.outcome IS NULL OR OLD.outcome='OUTCOME_UNKNOWN' OR
 NOT EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=OLD.tenant_id
 AND o.operation_id=OLD.invocation_id AND NOT o.legacy AND ((o.state IN('FAILED','SUCCEEDED') OR EXISTS(SELECT 1 FROM control_operation_outcomes v
 WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id AND v.state IN('SUCCEEDED','FAILED')
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))
 AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id
 AND v.state='RECONCILIATION_REQUIRED' AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))))))) THEN RAISE EXCEPTION 'business key identity, retention or recovery proof is immutable'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_business_key_history_insert_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN IF NOT EXISTS(SELECT 1 FROM control_business_keys k JOIN control_operations o
 ON o.tenant_id=k.tenant_id AND o.operation_id=k.invocation_id
 WHERE k.tenant_id IS NOT DISTINCT FROM NEW.tenant_id AND k.tool_id IS NOT DISTINCT FROM NEW.tool_id AND k.namespace IS NOT DISTINCT FROM NEW.namespace AND k.namespace_identity IS NOT DISTINCT FROM NEW.namespace_identity AND k.business_key_hash IS NOT DISTINCT FROM NEW.business_key_hash AND k.invocation_id IS NOT DISTINCT FROM NEW.invocation_id AND k.fingerprint IS NOT DISTINCT FROM NEW.fingerprint AND k.caller_agent_id IS NOT DISTINCT FROM NEW.caller_agent_id AND k.caller_user_id IS NOT DISTINCT FROM NEW.caller_user_id AND k.created_at IS NOT DISTINCT FROM NEW.created_at AND k.dedupe_expires_at IS NOT DISTINCT FROM NEW.dedupe_expires_at AND k.retain_until IS NOT DISTINCT FROM NEW.retain_until AND k.outcome IS NOT DISTINCT FROM NEW.outcome AND k.tombstoned_at IS NOT DISTINCT FROM NEW.tombstoned_at AND k.source='DEDUPE_WINDOW' AND k.dedupe_expires_at<=NEW.archived_at
 AND NOT o.legacy AND ((o.state IN('FAILED','SUCCEEDED') OR EXISTS(SELECT 1 FROM control_operation_outcomes v
 WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id AND v.state IN('SUCCEEDED','FAILED')
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))
 AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=o.tenant_id AND v.operation_id=o.operation_id
 AND v.state='RECONCILIATION_REQUIRED' AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id)))) THEN RAISE EXCEPTION 'window archive requires an exact resolved reservation'; END IF; RETURN NEW; END $$;



CREATE FUNCTION public.control_credential_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$ BEGIN RAISE EXCEPTION 'immutable credential history'; END $$;

CREATE FUNCTION public.control_effect_family_insert_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN IF NEW.current_generation<>1 THEN RAISE EXCEPTION 'effect starts at generation one'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_effect_generation_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'effect history is retained'; END IF;
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.effect_id<>OLD.effect_id OR NEW.generation<>OLD.generation OR NEW.operation_id<>OLD.operation_id OR NEW.attempt_id<>OLD.attempt_id OR NEW.auth_nonce<>OLD.auth_nonce OR NEW.device_id<>OLD.device_id OR NEW.broker_id<>OLD.broker_id OR NEW.handler_id<>OLD.handler_id OR NEW.issued_at<>OLD.issued_at OR NEW.expires_at<>OLD.expires_at OR NEW.outcome_deadline<>OLD.outcome_deadline OR NEW.revision<>OLD.revision+1 OR (OLD.consumer IS NOT NULL AND (NEW.consumer IS DISTINCT FROM OLD.consumer OR NEW.claim_token IS DISTINCT FROM OLD.claim_token)) OR (OLD.consumer IS NULL AND NEW.consumer IS NOT NULL AND NOT((OLD.state IN('AUTHORIZED','SENDING') AND NEW.state='CONSUMED') OR (OLD.state IN('AUTHORIZED','SENDING','AMBIGUOUS') AND (NEW.state='FAILED' AND NEW.consumer LIKE 'TARGET:%' AND EXISTS(
 SELECT 1 FROM control_effect_evidence proof WHERE proof.tenant_id=OLD.tenant_id AND proof.effect_id=OLD.effect_id
 AND proof.generation=OLD.generation AND proof.source='TARGET:'||NEW.consumer AND proof.outcome='FAILED'
 AND proof.result_digest='sha256:b775353541fcdd3e2ef3d940fbae7961712380d2390c74852a52e12487d8e7f2'))))) OR NOT((NEW.state=OLD.state) OR
 (OLD.state='AUTHORIZED' AND NEW.state IN('RELEASED','SENDING','CONSUMED','AMBIGUOUS')) OR
 (OLD.state='SENDING' AND NEW.state IN('CONSUMED','AMBIGUOUS')) OR
 (OLD.state IN('AUTHORIZED','SENDING') AND (NEW.state='FAILED' AND NEW.consumer LIKE 'TARGET:%' AND EXISTS(
 SELECT 1 FROM control_effect_evidence proof WHERE proof.tenant_id=OLD.tenant_id AND proof.effect_id=OLD.effect_id
 AND proof.generation=OLD.generation AND proof.source='TARGET:'||NEW.consumer AND proof.outcome='FAILED'
 AND proof.result_digest='sha256:b775353541fcdd3e2ef3d940fbae7961712380d2390c74852a52e12487d8e7f2'))) OR
 (OLD.state='CONSUMED' AND NEW.state IN('COMPLETED','FAILED','AMBIGUOUS')) OR
 (OLD.state='AMBIGUOUS' AND NEW.state IN('COMPLETED','FAILED')) OR
 (OLD.state IN('COMPLETED','FAILED') AND NEW.state='CONFLICT')) THEN RAISE EXCEPTION 'invalid effect identity or transition'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_effect_identity_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'effect history is retained'; END IF;
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.effect_id<>OLD.effect_id OR NEW.operation_id<>OLD.operation_id OR NEW.route_id<>OLD.route_id OR NEW.request_digest<>OLD.request_digest OR NEW.current_generation<>OLD.current_generation+1 OR NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=OLD.tenant_id AND g.effect_id=OLD.effect_id AND g.generation=OLD.current_generation AND (g.state='RELEASED' OR (g.state='AMBIGUOUS' AND EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id
 WHERE r.tenant_id=g.tenant_id AND r.operation_id=g.operation_id AND r.proof='TARGET_DEDUPLICATION'
 AND r.to_revision<=i.revision AND i.state='EXECUTING' AND i.document::jsonb->>'reservedNonce'<>g.attempt_id
 AND EXISTS(SELECT 1 FROM jsonb_array_elements(r.document::jsonb->'targetPlan') p(value) WHERE p.value->>'effectId'=g.effect_id AND (p.value->>'generation')::bigint=g.generation))))) THEN RAISE EXCEPTION 'invalid effect identity or transition'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_effect_insert_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN IF NEW.state<>'AUTHORIZED' OR NEW.revision<>1 OR NEW.consumer IS NOT NULL OR NOT EXISTS(SELECT 1 FROM control_effects e WHERE e.tenant_id=NEW.tenant_id AND e.effect_id=NEW.effect_id AND e.operation_id=NEW.operation_id AND e.current_generation=NEW.generation) THEN RAISE EXCEPTION 'generation must be current and new'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_effect_intent_capacity() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+octet_length(NEW.document)
 WHERE tenant_id=NEW.tenant_id; IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_effect_intent_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM control_effects e JOIN control_effect_generations g
 ON g.tenant_id=e.tenant_id AND g.effect_id=e.effect_id AND g.generation=e.current_generation
 WHERE e.tenant_id=NEW.tenant_id AND e.effect_id=NEW.effect_id AND e.request_digest=NEW.request_digest
 AND g.state='AUTHORIZED' AND g.issued_at=NEW.recorded_at)
 THEN RAISE EXCEPTION 'intent requires matching fresh effect authority'; END IF; RETURN NEW;
END $$;

CREATE FUNCTION public.control_effect_manifest_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN PERFORM 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id
 AND o.operation_id=NEW.operation_id FOR UPDATE;
 IF NOT(COALESCE(EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_enterprise_nonces n
 ON n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.nonce=NEW.attempt_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND i.state='EXECUTING'
 AND i.document::jsonb->>'reservedNonce'=NEW.attempt_id AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND n.consumed_at<=NEW.issued_at
 AND i.expires_at>NEW.issued_at AND NEW.expires_at<=i.expires_at
 AND (i.authorization_epoch=(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id) OR (i.authorization_epoch<(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id) AND (SELECT COUNT(*) FROM control_kill_events k WHERE k.tenant_id=i.tenant_id AND k.authorization_epoch>i.authorization_epoch AND k.authorization_epoch<=(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id))=(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id)-i.authorization_epoch))
 AND NOT EXISTS(SELECT 1 FROM control_kill_events k WHERE k.tenant_id=i.tenant_id AND k.event_kind='KILL' AND k.mode='TERMINATE'
 AND NOT EXISTS(SELECT 1 FROM control_kill_supersessions x WHERE x.tenant_id=k.tenant_id AND x.kill_event_id=k.event_id)
 AND (k.scope_kind='TENANT' OR NOT EXISTS(SELECT 1 FROM control_invocation_kill_scopes s WHERE s.tenant_id=i.tenant_id AND s.attempt_id=NEW.attempt_id)
 OR EXISTS(SELECT 1 FROM control_invocation_kill_scopes s WHERE s.tenant_id=i.tenant_id AND s.attempt_id=NEW.attempt_id AND ((k.scope_kind='PACKAGE' AND (s.package_id IS NULL OR s.package_id=k.scope_value)) OR (k.scope_kind='PACKAGE_DIGEST' AND (s.package_digest IS NULL OR s.package_digest=k.scope_value)) OR (k.scope_kind='PUBLISHER' AND (s.publisher_id IS NULL OR s.publisher_id=k.scope_value)) OR (k.scope_kind='TOOL' AND (s.tool_id IS NULL OR s.tool_id=k.scope_value)) OR (k.scope_kind='POOL' AND (s.pool_id IS NULL OR s.pool_id=k.scope_value)) OR (k.scope_kind='RUNTIME_IMAGE' AND (s.image_digest IS NULL OR s.image_digest=k.scope_value))))))
 AND NOT EXISTS(SELECT 1 FROM control_operation_cancellations c WHERE c.tenant_id=i.tenant_id AND c.invocation_id=i.invocation_id)
 AND NOT EXISTS(SELECT 1 FROM control_execution_leases l WHERE l.tenant_id=i.tenant_id AND l.invocation_id=i.invocation_id
 AND l.fencing_token=NEW.attempt_id AND l.expires_at<=NEW.issued_at))
 AND NOT EXISTS(SELECT 1 FROM control_attempt_manifest_seals m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id) AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.operation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED'),false)) THEN RAISE EXCEPTION 'EFFECT_MANIFEST_SEALED or inactive execution authority'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_effect_manifest_member() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN INSERT INTO control_attempt_effects(tenant_id,operation_id,attempt_id,effect_id)
 VALUES(NEW.tenant_id,NEW.operation_id,NEW.attempt_id,NEW.effect_id) ON CONFLICT DO NOTHING; RETURN NEW; END $$;

CREATE FUNCTION public.control_effect_manifest_transition_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF OLD.state<>NEW.state AND OLD.state IN('AUTHORIZED','SENDING') AND NEW.state IN('SENDING','CONSUMED') THEN
  PERFORM 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id
   AND o.operation_id=NEW.operation_id FOR UPDATE;
  IF NOT(COALESCE(EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_enterprise_nonces n
 ON n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.nonce=NEW.attempt_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND i.state='EXECUTING'
 AND i.document::jsonb->>'reservedNonce'=NEW.attempt_id AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND n.consumed_at<=NEW.issued_at
 AND i.expires_at>NEW.issued_at AND NEW.expires_at<=i.expires_at
 AND (i.authorization_epoch=(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id) OR (i.authorization_epoch<(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id) AND (SELECT COUNT(*) FROM control_kill_events k WHERE k.tenant_id=i.tenant_id AND k.authorization_epoch>i.authorization_epoch AND k.authorization_epoch<=(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id))=(SELECT authorization_epoch FROM control_tenant_authority t WHERE t.tenant_id=i.tenant_id)-i.authorization_epoch))
 AND NOT EXISTS(SELECT 1 FROM control_kill_events k WHERE k.tenant_id=i.tenant_id AND k.event_kind='KILL' AND k.mode='TERMINATE'
 AND NOT EXISTS(SELECT 1 FROM control_kill_supersessions x WHERE x.tenant_id=k.tenant_id AND x.kill_event_id=k.event_id)
 AND (k.scope_kind='TENANT' OR NOT EXISTS(SELECT 1 FROM control_invocation_kill_scopes s WHERE s.tenant_id=i.tenant_id AND s.attempt_id=NEW.attempt_id)
 OR EXISTS(SELECT 1 FROM control_invocation_kill_scopes s WHERE s.tenant_id=i.tenant_id AND s.attempt_id=NEW.attempt_id AND ((k.scope_kind='PACKAGE' AND (s.package_id IS NULL OR s.package_id=k.scope_value)) OR (k.scope_kind='PACKAGE_DIGEST' AND (s.package_digest IS NULL OR s.package_digest=k.scope_value)) OR (k.scope_kind='PUBLISHER' AND (s.publisher_id IS NULL OR s.publisher_id=k.scope_value)) OR (k.scope_kind='TOOL' AND (s.tool_id IS NULL OR s.tool_id=k.scope_value)) OR (k.scope_kind='POOL' AND (s.pool_id IS NULL OR s.pool_id=k.scope_value)) OR (k.scope_kind='RUNTIME_IMAGE' AND (s.image_digest IS NULL OR s.image_digest=k.scope_value))))))
 AND NOT EXISTS(SELECT 1 FROM control_operation_cancellations c WHERE c.tenant_id=i.tenant_id AND c.invocation_id=i.invocation_id)
 AND NOT EXISTS(SELECT 1 FROM control_execution_leases l WHERE l.tenant_id=i.tenant_id AND l.invocation_id=i.invocation_id
 AND l.fencing_token=NEW.attempt_id AND l.expires_at<=NEW.issued_at))
 AND NOT EXISTS(SELECT 1 FROM control_attempt_manifest_seals m WHERE m.tenant_id=NEW.tenant_id AND m.operation_id=NEW.operation_id AND m.attempt_id=NEW.attempt_id) AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.operation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED'),false)) THEN RAISE EXCEPTION 'EFFECT_MANIFEST_SEALED or inactive execution authority'; END IF;
 END IF; RETURN NEW;
END $$;

CREATE FUNCTION public.control_effect_target_binding_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM control_effects e JOIN control_effect_generations g
 ON g.tenant_id=e.tenant_id AND g.effect_id=e.effect_id AND g.generation=1
 WHERE e.tenant_id=NEW.tenant_id AND e.effect_id=NEW.effect_id AND e.operation_id=NEW.operation_id
 AND e.route_id=NEW.route_id AND e.current_generation=1 AND g.state='AUTHORIZED'
 AND g.issued_at=NEW.first_authorized_at)
 OR (NEW.document::jsonb->>'header') IS DISTINCT FROM NEW.header
 OR NEW.document::jsonb->>'retentionMs' IS NULL OR NEW.document::jsonb->>'reviewExpiresAtUnixMs' IS NULL
 OR NEW.valid_until>LEAST(NEW.first_authorized_at+(NEW.document::jsonb->>'retentionMs')::bigint
 -GREATEST(3600000,((NEW.document::jsonb->>'retentionMs')::bigint+9)/10),
 (NEW.document::jsonb->>'reviewExpiresAtUnixMs')::bigint)
 THEN RAISE EXCEPTION 'target key requires first immutable effect authorization'; END IF; RETURN NEW;
END $$;



CREATE FUNCTION public.control_epoch_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$ BEGIN IF NEW.tenant_id<>OLD.tenant_id OR NEW.revision<OLD.revision OR NEW.authorization_epoch<OLD.authorization_epoch OR NEW.directory_cache_generation<OLD.directory_cache_generation THEN RAISE EXCEPTION 'authorization cannot move backwards or change ownership'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_execution_evidence_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'execution evidence is immutable';
END;
$$;

CREATE FUNCTION public.control_execution_identity_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$ BEGIN IF NEW.tenant_id<>OLD.tenant_id OR NEW.invocation_id<>OLD.invocation_id OR NEW.device_id<>OLD.device_id OR (NEW.fencing_token<>OLD.fencing_token AND NOT(EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i
 ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id JOIN control_enterprise_nonces n
 ON n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.nonce=NEW.fencing_token
 WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.invocation_id AND r.to_revision<=i.revision
 AND i.state='EXECUTING' AND i.document::jsonb->>'reservedNonce'=NEW.fencing_token
 AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND NEW.expires_at<=i.expires_at
 AND (r.document::jsonb->>'lease')::jsonb->>'fencingToken'=OLD.fencing_token AND (r.document::jsonb->>'lease')::jsonb->>'deviceId'=OLD.device_id
 AND ((r.document::jsonb->>'lease')::jsonb->>'expiresAt')::bigint=OLD.expires_at AND EXISTS(SELECT 1 FROM jsonb_array_elements_text(r.attempt_ids::jsonb) AS j(value) WHERE j.value=OLD.fencing_token)))) THEN RAISE EXCEPTION 'execution identity requires archived recovery proof'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_execution_reconciliation_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NEW.purpose='EXECUTION' AND (TG_OP='INSERT' OR (OLD.consumed_at IS NULL AND NEW.consumed_at IS NOT NULL)) THEN
  PERFORM 1 FROM control_operations WHERE tenant_id=NEW.tenant_id AND operation_id=NEW.invocation_id FOR UPDATE;
  IF EXISTS(SELECT 1 FROM control_operation_outcomes v WHERE v.tenant_id=NEW.tenant_id AND v.operation_id=NEW.invocation_id
 AND v.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=v.tenant_id AND x.operation_id=v.operation_id) AND v.state='RECONCILIATION_REQUIRED')
 OR EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=NEW.tenant_id AND g.operation_id=NEW.invocation_id AND g.state='CONFLICT') THEN RAISE EXCEPTION 'reconciliation fences new execution authority'; END IF;
 END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_execution_report_qualification_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NEW.qualified_report THEN
  PERFORM 1 FROM control_enterprise_nonces n
  JOIN control_attempt_recovery_scopes s ON s.tenant_id=n.tenant_id AND s.operation_id=n.invocation_id AND s.attempt_id=n.nonce
  JOIN control_enterprise_invocations i ON i.tenant_id=n.tenant_id AND i.invocation_id=n.invocation_id
  JOIN control_endpoints ep ON ep.tenant_id=s.tenant_id AND ep.device_id=s.device_id
  JOIN control_records d ON d.tenant_id=s.tenant_id AND d.kind='DEVICE' AND d.record_id=s.device_id
  WHERE n.tenant_id=NEW.tenant_id AND n.invocation_id=NEW.invocation_id AND n.nonce=NEW.fencing_token
  AND n.purpose='EXECUTION' AND n.consumed_at IS NOT NULL AND n.consumed_at<=NEW.recorded_at
  AND s.device_id=i.document::jsonb->'evaluation'->'context'->>'deviceId'
  AND ep.document::jsonb->>'state'='ACTIVE' AND d.document::jsonb->>'enabled'='true'
  FOR SHARE OF ep,d;
  IF NOT FOUND THEN RAISE EXCEPTION 'qualified report requires independent current admission trust'; END IF;
 END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_fleet_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF TG_OP = 'DELETE' OR OLD.kind = 'RELEASE' OR NEW.tenant_id <> OLD.tenant_id
      OR NEW.kind <> OLD.kind OR NEW.record_id <> OLD.record_id OR NEW.revision <> OLD.revision + 1 THEN
    RAISE EXCEPTION 'fleet immutable identity or generation violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION public.control_invalidate_directory_cache() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        UPDATE public.control_tenant_authority SET directory_cache_generation=directory_cache_generation+1 WHERE tenant_id=OLD.tenant_id;
        IF NOT FOUND THEN RAISE EXCEPTION 'tenant authority missing'; END IF;
    END IF;
    IF TG_OP = 'INSERT' OR (TG_OP = 'UPDATE' AND NEW.tenant_id<>OLD.tenant_id) THEN
        UPDATE public.control_tenant_authority SET directory_cache_generation=directory_cache_generation+1 WHERE tenant_id=NEW.tenant_id;
        IF NOT FOUND THEN RAISE EXCEPTION 'tenant authority missing'; END IF;
    END IF;
    RETURN NULL;
END;
$$;



CREATE FUNCTION public.control_invocation_recovery_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$ BEGIN IF OLD.state IN('FAILED','EXPIRED','OUTCOME_UNKNOWN','CANCELLED','SUCCEEDED','PARTIAL') AND NEW.state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') AND NOT(NEW.state='QUEUED' AND NEW.revision=OLD.revision+1 AND NEW.tenant_id=OLD.tenant_id AND NEW.invocation_id=OLD.invocation_id AND NEW.request_digest=OLD.request_digest AND NEW.created_at=OLD.created_at AND NEW.expires_at=OLD.expires_at AND NEW.authorization_epoch=OLD.authorization_epoch
 AND NEW.document::jsonb->>'state'=NEW.state AND (NEW.document::jsonb->>'revision')::bigint=NEW.revision AND (NEW.document::jsonb->>'expiresAtUnixMs')::bigint=NEW.expires_at
 AND NEW.document::jsonb->'evaluation' IS NOT DISTINCT FROM OLD.document::jsonb->'evaluation'
 AND NEW.document::jsonb->>'reservedNonce' IS NULL AND NEW.document::jsonb->>'resultDigest' IS NULL
 AND NEW.document::jsonb->'completedResources'='[]'::jsonb
 AND EXISTS(SELECT 1 FROM control_operation_recoveries r WHERE r.tenant_id=OLD.tenant_id
 AND r.operation_id=OLD.invocation_id AND r.from_revision=OLD.revision AND r.to_revision=NEW.revision
 AND r.original_deadline=OLD.expires_at AND r.document::jsonb->>'invocation'=OLD.document)) THEN RAISE EXCEPTION 'terminal invocation requires positive recovery proof'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_invocation_result_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'invocation results are purged, never deleted'; END IF;
    IF OLD.purged_at IS NOT NULL OR NEW.payload IS NOT NULL OR NEW.purged_at IS NULL
       OR NEW.tenant_id <> OLD.tenant_id OR NEW.invocation_id <> OLD.invocation_id
       OR NEW.fencing_nonce <> OLD.fencing_nonce OR NEW.result_digest <> OLD.result_digest
       OR NEW.byte_length <> OLD.byte_length OR NEW.created_at <> OLD.created_at
       OR NEW.retain_until <> OLD.retain_until THEN
        RAISE EXCEPTION 'invocation result is immutable except for its purge';
    END IF;
    RETURN NEW;
END $$;













CREATE FUNCTION public.control_nonce_identity_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.nonce<>OLD.nonce OR NEW.invocation_id<>OLD.invocation_id
 OR NEW.evaluation_digest<>OLD.evaluation_digest OR NEW.authorization_epoch<>OLD.authorization_epoch
 OR NEW.issued_at<>OLD.issued_at OR NEW.expires_at<>OLD.expires_at OR NEW.purpose<>OLD.purpose
 OR (OLD.consumed_at IS NOT NULL AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at)
 OR (NEW.consumed_at IS NOT NULL AND (NEW.consumed_at<OLD.issued_at OR NEW.consumed_at>=OLD.expires_at))
 OR (NEW.purpose<>'SERVICE' AND OLD.consumed_at IS NULL AND NEW.consumed_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM control_operation_attempts a WHERE a.tenant_id=NEW.tenant_id AND a.attempt_id=NEW.nonce))
 OR (OLD.consumed_at IS NULL AND NEW.consumed_at IS NOT NULL AND EXISTS(SELECT 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.invocation_id AND o.state='FAILED'))
 THEN RAISE EXCEPTION 'nonce identity and consumption are immutable'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_operation_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'operation is retained'; END IF;
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.operation_id<>OLD.operation_id OR NEW.request_digest<>OLD.request_digest
 OR NEW.created_at<>OLD.created_at OR NEW.legacy<>OLD.legacy OR NEW.revision<>OLD.revision+1
 OR (OLD.state='FAILED' AND NEW.state<>'FAILED' AND NOT(NEW.state='RUNNING' AND EXISTS(SELECT 1 FROM control_operation_recoveries r JOIN control_enterprise_invocations i
 ON i.tenant_id=r.tenant_id AND i.invocation_id=r.operation_id WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.operation_id
 AND r.operation_revision=OLD.revision AND i.revision=r.to_revision AND i.state='QUEUED' AND i.expires_at=r.original_deadline))) THEN RAISE EXCEPTION 'operation identity or finality is immutable'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_operation_outcome_capacity() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
 BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+octet_length(NEW.document) WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_operation_outcome_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
 BEGIN PERFORM 1 FROM control_operations o WHERE o.tenant_id=NEW.tenant_id AND o.operation_id=NEW.operation_id FOR UPDATE;
 IF NOT(COALESCE(EXISTS(SELECT 1 FROM control_enterprise_invocations i WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND i.revision=NEW.invocation_revision)
 AND NEW.revision=COALESCE((SELECT max(p.revision) FROM control_operation_outcomes p WHERE p.tenant_id=NEW.tenant_id AND p.operation_id=NEW.operation_id),0)+1
 AND NEW.document::jsonb->>'operationId'=NEW.operation_id AND NEW.document::jsonb->>'state'=NEW.state
 AND (NEW.document::jsonb->>'revision')::bigint=NEW.revision AND (NEW.document::jsonb->>'updatedAtUnixMs')::bigint=NEW.recorded_at
 AND NEW.document::jsonb->'retryable'='false'::jsonb
 AND ((NEW.state NOT IN('SUCCEEDED','PARTIAL','FAILED') AND NEW.document::jsonb->'finalized'='false'::jsonb)
 OR (NEW.state IN('SUCCEEDED','PARTIAL','FAILED') AND NEW.document::jsonb->'finalized'='true'::jsonb
 AND NEW.document::jsonb->'journalComplete'='true'::jsonb AND (NEW.document::jsonb->>'unresolvedEffectCount')::bigint=0 AND (NEW.document::jsonb->>'unsettledAttemptCount')::bigint=0 AND EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_operations o ON o.tenant_id=i.tenant_id AND o.operation_id=i.invocation_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND NOT o.legacy
 AND i.state NOT IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING')
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n LEFT JOIN control_operation_attempts a ON a.tenant_id=n.tenant_id AND a.attempt_id=n.nonce
 WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE'
 AND (n.purpose<>'EXECUTION' OR a.attempt_id IS NULL OR a.operation_id<>n.invocation_id OR a.evaluation_digest<>n.evaluation_digest
 OR a.authorization_epoch<>n.authorization_epoch OR a.issued_at<>n.issued_at OR a.expires_at<>n.expires_at
 OR (n.consumed_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM control_attempt_settlements s WHERE s.tenant_id=n.tenant_id AND s.operation_id=n.invocation_id AND s.attempt_id=n.nonce) AND NOT EXISTS(SELECT 1 FROM control_attempt_reviews q WHERE q.tenant_id=n.tenant_id AND q.operation_id=n.invocation_id AND q.attempt_id=n.nonce))))
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id AND
 ((g.state NOT IN('RELEASED','COMPLETED','FAILED') AND NOT(g.state='AMBIGUOUS' AND EXISTS(SELECT 1 FROM control_effect_target_bindings b
 JOIN control_effect_intents intent ON intent.tenant_id=b.tenant_id AND intent.effect_id=b.effect_id AND intent.profile_digest=b.profile_digest
 JOIN control_effect_evidence witness ON witness.tenant_id=b.tenant_id AND witness.effect_id=b.effect_id
 WHERE b.tenant_id=g.tenant_id AND b.effect_id=g.effect_id AND witness.generation=g.generation
 AND b.document::jsonb->>'statusRouteId' IS NOT NULL AND substr(witness.source,1,14)='TARGET_LOOKUP:' AND length(witness.source)=78
 AND NOT EXISTS(SELECT 1 FROM control_effect_evidence other WHERE other.tenant_id=witness.tenant_id AND other.effect_id=witness.effect_id
 AND other.generation=witness.generation AND substr(other.source,1,14)='TARGET_LOOKUP:'
 AND (other.source<>witness.source OR other.outcome<>witness.outcome OR other.result_digest<>witness.result_digest))))) OR (g.state IN('COMPLETED','FAILED') AND NOT EXISTS(
 SELECT 1 FROM control_effect_evidence e WHERE e.tenant_id=g.tenant_id AND e.effect_id=g.effect_id AND e.generation=g.generation
 AND e.source='TARGET:'||g.consumer AND e.outcome=g.state))))
 AND (NEW.state<>'SUCCEEDED' OR EXISTS(SELECT 1 FROM control_attempt_snapshots h
 WHERE h.tenant_id=i.tenant_id AND h.operation_id=i.invocation_id
 AND (EXISTS(SELECT 1 FROM control_attempt_settlements s WHERE s.tenant_id=h.tenant_id AND s.operation_id=h.operation_id AND s.attempt_id=h.attempt_id)
 OR EXISTS(SELECT 1 FROM control_attempt_reviews q WHERE q.tenant_id=h.tenant_id AND q.operation_id=h.operation_id AND q.attempt_id=h.attempt_id)) AND (h.state='SUCCEEDED' OR EXISTS(SELECT 1 FROM control_execution_evidence e WHERE e.tenant_id=h.tenant_id
 AND e.invocation_id=h.operation_id AND e.fencing_token=h.attempt_id AND e.qualified_report=true
 AND e.document::jsonb->>'outcome'='COMPLETED' AND e.document::jsonb->'result'->>'kind'='RESULT' AND e.document::jsonb->'result'->>'resultType'='complete' AND e.document::jsonb->'result'->>'isError'='false'))
 AND h.revision=(SELECT max(x.revision) FROM control_attempt_snapshots x WHERE x.tenant_id=h.tenant_id AND x.attempt_id=h.attempt_id)))
)))
 AND NOT EXISTS(SELECT 1 FROM control_operation_outcomes p WHERE p.tenant_id=NEW.tenant_id AND p.operation_id=NEW.operation_id
 AND p.revision=(SELECT max(x.revision) FROM control_operation_outcomes x WHERE x.tenant_id=p.tenant_id AND x.operation_id=p.operation_id)
 AND ((p.state='RECONCILIATION_REQUIRED' AND NEW.state<>'RECONCILIATION_REQUIRED')
 OR (p.state IN('SUCCEEDED','PARTIAL','FAILED') AND NEW.state<>p.state AND NEW.state<>'RECONCILIATION_REQUIRED'
 AND NOT(p.state='FAILED' AND NEW.state='RUNNING' AND EXISTS(SELECT 1 FROM control_operation_recoveries r
 WHERE r.tenant_id=p.tenant_id AND r.operation_id=p.operation_id AND r.to_revision>p.invocation_revision AND r.to_revision<=NEW.invocation_revision))))),false)) THEN RAISE EXCEPTION 'derived outcome requires complete evidence and immutable finality'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_operation_recovery_capacity() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
BEGIN
 UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+octet_length(NEW.document)
 WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_operation_recovery_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'operation recovery is immutable'; END IF;
 IF NOT EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_operations o
 ON o.tenant_id=i.tenant_id AND o.operation_id=i.invocation_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id
 AND i.revision=NEW.from_revision AND o.revision=NEW.operation_revision AND NOT o.legacy
 AND i.state IN('FAILED','EXPIRED','OUTCOME_UNKNOWN') AND o.state IN('FAILED','OUTCOME_UNKNOWN')
 AND (i.authorization_epoch=(SELECT authorization_epoch FROM control_tenant_authority WHERE tenant_id=i.tenant_id) OR (i.authorization_epoch<(SELECT authorization_epoch FROM control_tenant_authority WHERE tenant_id=i.tenant_id) AND (SELECT COUNT(*) FROM control_kill_events k WHERE k.tenant_id=i.tenant_id AND k.authorization_epoch>i.authorization_epoch AND k.authorization_epoch<=(SELECT authorization_epoch FROM control_tenant_authority WHERE tenant_id=i.tenant_id))=(SELECT authorization_epoch FROM control_tenant_authority WHERE tenant_id=i.tenant_id)-i.authorization_epoch))
 AND i.expires_at=NEW.original_deadline AND i.expires_at>NEW.created_at
 AND i.document::jsonb->>'reservedNonce' IS NOT DISTINCT FROM NEW.previous_nonce
 AND NEW.document::jsonb->>'invocation'=i.document
 AND NEW.document::jsonb->'attemptIds'=NEW.attempt_ids::jsonb
 AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.attempt_ids::jsonb) j(value) WHERE jsonb_typeof(j.value)<>'string')
 AND NOT EXISTS(SELECT 1 FROM control_operation_cancellations c WHERE c.tenant_id=i.tenant_id AND c.invocation_id=i.invocation_id)
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_approvals a WHERE a.tenant_id=i.tenant_id AND a.invocation_id=i.invocation_id AND a.approval_type='OPERATION' AND (a.document::jsonb->>'state') IN('DENIED','REVOKED'))
 AND NOT EXISTS(SELECT 1 FROM control_business_keys k WHERE k.tenant_id=i.tenant_id AND k.invocation_id=i.invocation_id
 AND (k.tombstoned_at IS NOT NULL OR k.retain_until<=NEW.created_at))
 AND (i.document::jsonb->>'reservedNonce' IS NULL OR EXISTS(SELECT 1 FROM control_operation_attempts a
 WHERE a.tenant_id=i.tenant_id AND a.operation_id=i.invocation_id AND a.attempt_id=i.document::jsonb->>'reservedNonce'))
 AND (SELECT count(*) FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE')<64
 AND (SELECT count(*) FROM control_effect_generations g WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id)<=4096
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n LEFT JOIN control_operation_attempts a
 ON a.tenant_id=n.tenant_id AND a.attempt_id=n.nonce WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id
 AND n.purpose<>'SERVICE' AND (n.purpose<>'EXECUTION' OR NOT(a.attempt_id IS NOT NULL AND a.operation_id=n.invocation_id
 AND a.evaluation_digest=n.evaluation_digest AND a.authorization_epoch=n.authorization_epoch
 AND a.issued_at=n.issued_at AND a.expires_at=n.expires_at)
 OR (n.consumed_at IS NULL AND n.expires_at>NEW.created_at)
 OR (n.consumed_at IS NOT NULL AND n.consumed_at>NEW.created_at)))
 AND (SELECT count(*) FROM jsonb_array_elements_text(NEW.attempt_ids::jsonb) AS j(value))=(SELECT count(*) FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE')
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE'
 AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements_text(NEW.attempt_ids::jsonb) AS j(value) WHERE j.value=n.nonce))
 AND (
 (NEW.proof='NEVER_CONSUMED' AND o.state='FAILED'
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE' AND n.consumed_at IS NOT NULL)
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id))
 OR (NEW.proof='QUALIFIED_BROKER_NO_WRITE'
 AND EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE' AND n.consumed_at IS NOT NULL)
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE' AND n.consumed_at IS NOT NULL
 AND NOT EXISTS(SELECT 1 FROM control_attempt_recovery_scopes s WHERE s.tenant_id=n.tenant_id AND s.operation_id=n.invocation_id AND s.attempt_id=n.nonce
 AND s.device_id=i.document::jsonb->'evaluation'->'context'->>'deviceId'))
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g JOIN control_enterprise_nonces n
 ON n.tenant_id=g.tenant_id AND n.nonce=g.attempt_id
 WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id AND (g.state<>'RELEASED' OR n.consumed_at IS NULL)))
 OR (NEW.proof='TARGET_DEDUPLICATION'
 AND EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE' AND n.consumed_at IS NOT NULL)
 AND NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n WHERE n.tenant_id=i.tenant_id AND n.invocation_id=i.invocation_id AND n.purpose<>'SERVICE' AND n.consumed_at IS NOT NULL
 AND NOT EXISTS(SELECT 1 FROM control_attempt_recovery_scopes s WHERE s.tenant_id=n.tenant_id AND s.operation_id=n.invocation_id AND s.attempt_id=n.nonce
 AND s.device_id=i.document::jsonb->'evaluation'->'context'->>'deviceId'))
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations g JOIN control_enterprise_nonces n
 ON n.tenant_id=g.tenant_id AND n.nonce=g.attempt_id
 WHERE g.tenant_id=i.tenant_id AND g.operation_id=i.invocation_id AND (g.state NOT IN('RELEASED','AMBIGUOUS') OR n.consumed_at IS NULL))
 AND jsonb_typeof(NEW.document::jsonb->'targetPlan')='array'
 AND (SELECT count(*) FROM jsonb_array_elements(NEW.document::jsonb->'targetPlan') p(value)) BETWEEN 1 AND 256
 AND (SELECT count(*) FROM jsonb_array_elements(NEW.document::jsonb->'targetPlan') p(value))=(SELECT count(*) FROM control_effects e WHERE e.tenant_id=i.tenant_id AND e.operation_id=i.invocation_id)
 AND EXISTS(SELECT 1 FROM control_effect_generations g JOIN control_effects e ON e.tenant_id=g.tenant_id AND e.effect_id=g.effect_id WHERE e.tenant_id=i.tenant_id AND e.operation_id=i.invocation_id AND g.generation=e.current_generation AND g.state='AMBIGUOUS')
 AND NOT EXISTS(SELECT 1 FROM control_effects e JOIN control_effect_generations g ON g.tenant_id=e.tenant_id AND g.effect_id=e.effect_id AND g.generation=e.current_generation
 WHERE e.tenant_id=i.tenant_id AND e.operation_id=i.invocation_id AND NOT EXISTS(SELECT 1 FROM control_effect_target_bindings b
 WHERE b.tenant_id=e.tenant_id AND b.effect_id=e.effect_id AND b.valid_until>NEW.created_at
 AND EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.document::jsonb->'targetPlan') p(value) JOIN control_configuration_changes c ON c.tenant_id=e.tenant_id AND c.change_id=p.value->>'reviewId'
 WHERE p.value->>'effectId'=e.effect_id AND (p.value->>'generation')::bigint=e.current_generation AND p.value->>'requestDigest'=e.request_digest
 AND p.value->>'profileDigest'=b.profile_digest AND p.value->>'guaranteeDigest'=b.guarantee_digest
 AND c.document::jsonb->>'state'='APPLIED' AND (c.document::jsonb->>'requiredReviews')::bigint>=1 AND (SELECT count(*) FROM jsonb_array_elements(c.document::jsonb->'reviews') cr(value) WHERE cr.value->>'reviewerUserId'<>c.document::jsonb->>'requesterUserId')>=(c.document::jsonb->>'requiredReviews')::bigint)))
))
) THEN RAISE EXCEPTION 'positive complete Control recovery proof required'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_original_deadline_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN IF NEW.expires_at<>OLD.expires_at AND
 (EXISTS(SELECT 1 FROM control_operation_attempts a WHERE a.tenant_id=OLD.tenant_id AND a.operation_id=OLD.invocation_id)
 OR EXISTS(SELECT 1 FROM control_operation_recoveries r WHERE r.tenant_id=OLD.tenant_id AND r.operation_id=OLD.invocation_id)) THEN RAISE EXCEPTION 'admitted operation deadline is immutable'; END IF;RETURN NEW;END $$;



CREATE FUNCTION public.control_reconciliation_history_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'reconciliation history is retained'; END IF;
 IF NOT(COALESCE(NEW.document::jsonb->>'id'=NEW.reconciliation_id AND (NEW.document::jsonb->>'revision')::bigint=NEW.revision AND NEW.revision BETWEEN 1 AND 9007199254740991 AND length(NEW.document::jsonb->>'requesterUserId') BETWEEN 1 AND 128 AND EXISTS(SELECT 1 FROM control_enterprise_invocations i WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.document::jsonb->>'invocationId'),false)) THEN RAISE EXCEPTION 'invalid reconciliation identity'; END IF;
 IF TG_OP='INSERT' THEN
  IF NOT(COALESCE(NEW.revision=1 AND NEW.document::jsonb->>'state'='PENDING' AND NEW.document::jsonb->>'reviewerUserId' IS NULL,false)) THEN RAISE EXCEPTION 'reconciliation must begin pending'; END IF;
 ELSE
  IF NOT(COALESCE(NEW.tenant_id=OLD.tenant_id AND NEW.reconciliation_id=OLD.reconciliation_id AND NEW.revision=OLD.revision+1 AND OLD.document::jsonb->>'state'='PENDING' AND NEW.document::jsonb->>'state' IN('APPLIED','DENIED') AND length(NEW.document::jsonb->>'reviewerUserId') BETWEEN 1 AND 128 AND NEW.document::jsonb->>'id' IS NOT DISTINCT FROM OLD.document::jsonb->>'id' AND NEW.document::jsonb->>'invocationId' IS NOT DISTINCT FROM OLD.document::jsonb->>'invocationId' AND NEW.document::jsonb->>'requesterUserId' IS NOT DISTINCT FROM OLD.document::jsonb->>'requesterUserId' AND NEW.document::jsonb->>'requestDigest' IS NOT DISTINCT FROM OLD.document::jsonb->>'requestDigest' AND NEW.document::jsonb->'request' IS NOT DISTINCT FROM OLD.document::jsonb->'request',false)) THEN RAISE EXCEPTION 'reconciliation proposal and decision are immutable'; END IF;
 END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_recovery_scope_capacity() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
BEGIN
 UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,
 stored_bytes=stored_bytes+octet_length(NEW.device_id||NEW.pool_id||NEW.image_digest||NEW.runtime_digest||NEW.descriptor_digest||NEW.profile_digest)
 WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_recovery_scope_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'recovery scope is immutable'; END IF;
 IF NOT EXISTS(SELECT 1 FROM control_enterprise_nonces n JOIN control_enterprise_invocations i
 ON i.tenant_id=n.tenant_id AND i.invocation_id=n.invocation_id
 WHERE n.tenant_id=NEW.tenant_id AND n.invocation_id=NEW.operation_id AND n.nonce=NEW.attempt_id
 AND n.purpose='EXECUTION' AND n.consumed_at IS NULL AND i.state='RESERVED'
 AND i.document::jsonb->>'reservedNonce'=NEW.attempt_id
 AND i.document::jsonb->'evaluation'->'context'->>'deviceId'=NEW.device_id)
 THEN RAISE EXCEPTION 'recovery scope must bind fresh Control admission'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_review_owners_capacity() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,stored_bytes=stored_bytes+octet_length(NEW.document) WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;

CREATE FUNCTION public.control_review_owners_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM control_enterprise_invocations i JOIN control_tenant_authority t ON t.tenant_id=i.tenant_id
 WHERE i.tenant_id=NEW.tenant_id AND i.invocation_id=NEW.operation_id AND i.revision=1 AND t.revision=NEW.directory_revision AND (i.document::jsonb->'evaluation'->>'authorityRevision')::bigint=NEW.directory_revision
 AND NEW.document::jsonb->>'format'='toolgate-review-owners-v1' AND NEW.document::jsonb->>'operationId'=NEW.operation_id
 AND jsonb_typeof(NEW.document::jsonb->'owners')='array' AND jsonb_array_length(NEW.document::jsonb->'owners') BETWEEN 1 AND 66 AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.document::jsonb->'owners') j WHERE jsonb_typeof(j.value)<>'string') AND NOT EXISTS(SELECT 1 FROM (SELECT i.document::jsonb->'evaluation'->'context'->>'userId' AS principal WHERE i.document::jsonb->'evaluation'->'context'->>'userId' IS NOT NULL UNION SELECT d.document::jsonb->>'ownerUserId' FROM control_records d WHERE d.tenant_id=i.tenant_id AND d.kind='AGENT' AND d.record_id IN(SELECT i.document::jsonb->'evaluation'->'context'->>'agentId' UNION SELECT h.value->>'agentId' FROM jsonb_array_elements(i.document::jsonb->'evaluation'->'context'->'chain') h)) owners WHERE owners.principal IS NULL) AND jsonb_array_length(NEW.document::jsonb->'owners')=(SELECT count(*) FROM (SELECT i.document::jsonb->'evaluation'->'context'->>'userId' AS principal WHERE i.document::jsonb->'evaluation'->'context'->>'userId' IS NOT NULL UNION SELECT d.document::jsonb->>'ownerUserId' FROM control_records d WHERE d.tenant_id=i.tenant_id AND d.kind='AGENT' AND d.record_id IN(SELECT i.document::jsonb->'evaluation'->'context'->>'agentId' UNION SELECT h.value->>'agentId' FROM jsonb_array_elements(i.document::jsonb->'evaluation'->'context'->'chain') h)) owners) AND NOT EXISTS(SELECT 1 FROM (SELECT i.document::jsonb->'evaluation'->'context'->>'userId' AS principal WHERE i.document::jsonb->'evaluation'->'context'->>'userId' IS NOT NULL UNION SELECT d.document::jsonb->>'ownerUserId' FROM control_records d WHERE d.tenant_id=i.tenant_id AND d.kind='AGENT' AND d.record_id IN(SELECT i.document::jsonb->'evaluation'->'context'->>'agentId' UNION SELECT h.value->>'agentId' FROM jsonb_array_elements(i.document::jsonb->'evaluation'->'context'->'chain') h)) owners WHERE owners.principal NOT IN(SELECT value FROM jsonb_array_elements_text(NEW.document::jsonb->'owners'))))
 THEN RAISE EXCEPTION 'review owners require original admission'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_secret_version_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.name<>OLD.name OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'invalid secret version transition'; END IF;
    RETURN NEW;
END $$;







CREATE FUNCTION public.control_snapshot_transition_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM control_attempt_snapshots s WHERE s.tenant_id=NEW.tenant_id AND s.attempt_id=NEW.attempt_id
 AND (s.revision>=NEW.revision OR (s.state IN('SUCCEEDED','FAILED','PARTIAL','CANCELLED','EXPIRED') AND s.state<>NEW.state)
 OR (s.state='OUTCOME_UNKNOWN' AND NEW.state IN('PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING'))))
 THEN RAISE EXCEPTION 'attempt cannot reopen or rewrite history'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.control_tenant_access(write_lock boolean) RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE state text;
BEGIN
    IF write_lock THEN
        SELECT status INTO state FROM public.control_tenants
          WHERE tenant_id=NULLIF(current_setting('app.tenant_id',true),'') FOR SHARE;
    ELSE
        SELECT status INTO state FROM public.control_tenants
          WHERE tenant_id=NULLIF(current_setting('app.tenant_id',true),'');
    END IF;
    RETURN COALESCE(state='ACTIVE' OR (state IS NOT NULL AND pg_has_role(session_user,'toolgate_control_platform','MEMBER')),false);
END;
$$;

CREATE FUNCTION public.control_tenant_admit(tenant text, write_lock boolean) RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE state text;
BEGIN
    IF tenant IS DISTINCT FROM NULLIF(current_setting('app.tenant_id',true),'') THEN RETURN false; END IF;
    IF write_lock THEN
        SELECT status INTO state FROM public.control_tenants WHERE tenant_id=tenant FOR SHARE;
    ELSE
        SELECT status INTO state FROM public.control_tenants WHERE tenant_id=tenant;
    END IF;
    RETURN COALESCE(state='ACTIVE',false);
END;
$$;

CREATE FUNCTION public.control_tenant_identity_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='DELETE' OR NEW.tenant_id<>OLD.tenant_id THEN RAISE EXCEPTION 'tenant identity is immutable'; END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.control_tenant_identity_key_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'identity key retention requires explicit offboarding'; END IF;
    IF NEW.tenant_id<>OLD.tenant_id OR NEW.purpose<>OLD.purpose OR NEW.key_id<>OLD.key_id OR
       NEW.provider_reference<>OLD.provider_reference OR NEW.public_material<>OLD.public_material OR NEW.key_fingerprint<>OLD.key_fingerprint OR
       NEW.generation<OLD.generation OR (OLD.state='RETIRED' AND NEW.state<>'RETIRED') THEN RAISE EXCEPTION 'invalid identity key transition'; END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION public.control_tenant_lifecycle_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.created_at<>OLD.created_at OR NEW.updated_at<OLD.updated_at OR (NEW.status<>OLD.status AND NOT (
        (OLD.status='PROVISIONING' AND NEW.status='ACTIVE') OR (OLD.status='ACTIVE' AND NEW.status='SUSPENDED') OR
        (OLD.status='SUSPENDED' AND NEW.status='ACTIVE'))) THEN RAISE EXCEPTION 'invalid tenant lifecycle transition'; END IF;
    RETURN NEW;
END;
$$;



CREATE FUNCTION public.control_vault_key_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'vault key retention requires explicit offboarding'; END IF;
    IF NEW.version<>OLD.version OR NEW.tenant_id<>OLD.tenant_id OR OLD.state='RETIRED' OR
       (NEW.state<>OLD.state AND NOT ((OLD.state='ACTIVE' AND NEW.state='READ_ONLY') OR (OLD.state='READ_ONLY' AND NEW.state='RETIRED'))) OR
       (NEW.state='RETIRED' AND EXISTS(SELECT 1 FROM control_secrets WHERE tenant_id=OLD.tenant_id AND key_version=OLD.version))
       THEN RAISE EXCEPTION 'invalid vault key transition'; END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION public.effect_target_binding_capacity() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
BEGIN UPDATE public.control_tenant_capacity SET stored_rows=stored_rows+1,
 stored_bytes=stored_bytes+octet_length(NEW.document)+length(NEW.target_key)+length(NEW.header)
 WHERE tenant_id=NEW.tenant_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity unavailable'; END IF; RETURN NEW; END $$;

SET default_tablespace = '';

SET default_table_access_method = heap;

CREATE TABLE public.control_approval_clocks (
    tenant_id character varying(128) NOT NULL,
    observed_at bigint NOT NULL,
    CONSTRAINT control_approval_clocks_observed_at_check CHECK (((observed_at >= 0) AND (observed_at <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_approval_clocks FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_attempt_effects (
    tenant_id character varying(128) NOT NULL,
    operation_id character varying(128) NOT NULL,
    attempt_id character varying(128) NOT NULL,
    effect_id character varying(128) NOT NULL
);

ALTER TABLE ONLY public.control_attempt_effects FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_attempt_manifest_seals (
    tenant_id character varying(128) NOT NULL,
    operation_id character varying(128) NOT NULL,
    attempt_id character varying(128) NOT NULL,
    invocation_revision bigint NOT NULL,
    created_at bigint NOT NULL,
    effect_count bigint NOT NULL,
    manifest_digest text NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_attempt_manifest_seals_created_at_check CHECK (((created_at >= 0) AND (created_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_attempt_manifest_seals_document_check CHECK (((octet_length(document) <= 1048576) AND (jsonb_typeof((document)::jsonb) = 'object'::text))),
    CONSTRAINT control_attempt_manifest_seals_effect_count_check CHECK (((effect_count >= 0) AND (effect_count <= 4096))),
    CONSTRAINT control_attempt_manifest_seals_invocation_revision_check CHECK ((invocation_revision >= 1)),
    CONSTRAINT control_attempt_manifest_seals_manifest_digest_check CHECK ((manifest_digest ~ '^[a-f0-9]{64}$'::text))
);

ALTER TABLE ONLY public.control_attempt_manifest_seals FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_attempt_recovery_scopes (
    tenant_id text NOT NULL,
    operation_id text NOT NULL,
    attempt_id text NOT NULL,
    device_id text NOT NULL,
    pool_id text NOT NULL,
    image_digest text NOT NULL,
    runtime_digest text NOT NULL,
    descriptor_digest text NOT NULL,
    profile_digest text NOT NULL,
    profile_revision bigint NOT NULL,
    CONSTRAINT control_attempt_recovery_scopes_descriptor_digest_check CHECK ((length(descriptor_digest) = 71)),
    CONSTRAINT control_attempt_recovery_scopes_image_digest_check CHECK ((length(image_digest) = 71)),
    CONSTRAINT control_attempt_recovery_scopes_profile_digest_check CHECK ((length(profile_digest) = 71)),
    CONSTRAINT control_attempt_recovery_scopes_profile_revision_check CHECK (((profile_revision >= 1) AND (profile_revision <= '9007199254740991'::bigint))),
    CONSTRAINT control_attempt_recovery_scopes_runtime_digest_check CHECK ((length(runtime_digest) = 71))
);

ALTER TABLE ONLY public.control_attempt_recovery_scopes FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_attempt_reviews (
    tenant_id character varying(128) NOT NULL,
    operation_id character varying(128) NOT NULL,
    attempt_id character varying(128) NOT NULL,
    reconciliation_id character varying(128) NOT NULL,
    expected_outcome_revision bigint NOT NULL,
    recorded_at bigint NOT NULL,
    source_digest text NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_attempt_reviews_document_check CHECK (((octet_length(document) <= 1048576) AND (jsonb_typeof((document)::jsonb) = 'object'::text))),
    CONSTRAINT control_attempt_reviews_expected_outcome_revision_check CHECK ((expected_outcome_revision >= 1)),
    CONSTRAINT control_attempt_reviews_recorded_at_check CHECK (((recorded_at >= 0) AND (recorded_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_attempt_reviews_source_digest_check CHECK ((source_digest ~ '^[a-f0-9]{64}$'::text))
);

ALTER TABLE ONLY public.control_attempt_reviews FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_attempt_settlements (
    tenant_id character varying(128) NOT NULL,
    operation_id character varying(128) NOT NULL,
    attempt_id character varying(128) NOT NULL,
    proof text NOT NULL,
    invocation_revision bigint NOT NULL,
    recorded_at bigint NOT NULL,
    source_digest text NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_attempt_settlements_document_check CHECK (((octet_length(document) <= 1048576) AND (jsonb_typeof((document)::jsonb) = 'object'::text))),
    CONSTRAINT control_attempt_settlements_invocation_revision_check CHECK ((invocation_revision >= 1)),
    CONSTRAINT control_attempt_settlements_proof_check CHECK ((proof = ANY (ARRAY['NEVER_CONSUMED'::text, 'QUALIFIED_BROKER_ONLY'::text]))),
    CONSTRAINT control_attempt_settlements_recorded_at_check CHECK (((recorded_at >= 0) AND (recorded_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_attempt_settlements_source_digest_check CHECK ((source_digest ~ '^[a-f0-9]{64}$'::text))
);

ALTER TABLE ONLY public.control_attempt_settlements FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_attempt_snapshots (
    tenant_id character varying(128) NOT NULL,
    operation_id character varying(128) NOT NULL,
    attempt_id character varying(128) NOT NULL,
    revision bigint NOT NULL,
    state text NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_attempt_snapshots_check CHECK (((octet_length(document) <= 5242880) AND (COALESCE(((document)::jsonb ->> 'id'::text), ''::text) = (operation_id)::text) AND (COALESCE(((document)::jsonb ->> 'reservedNonce'::text), ''::text) = (attempt_id)::text) AND (COALESCE(((document)::jsonb ->> 'state'::text), ''::text) = state))),
    CONSTRAINT control_attempt_snapshots_revision_check CHECK ((revision >= 1)),
    CONSTRAINT control_attempt_snapshots_state_check CHECK ((state = ANY (ARRAY['PENDING_APPROVAL'::text, 'QUEUED'::text, 'RESERVED'::text, 'DISPATCHED'::text, 'EXECUTING'::text, 'SUCCEEDED'::text, 'PARTIAL'::text, 'FAILED'::text, 'OUTCOME_UNKNOWN'::text, 'CANCELLED'::text, 'EXPIRED'::text])))
);

ALTER TABLE ONLY public.control_attempt_snapshots FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_audit (
    sequence bigint NOT NULL,
    tenant_id character varying(128) NOT NULL,
    chain_index bigint NOT NULL,
    previous_digest character(64) NOT NULL,
    actor_id character varying(64) NOT NULL,
    operation character varying(40) NOT NULL,
    target character varying(256) NOT NULL,
    revision bigint NOT NULL,
    request_id character varying(128) NOT NULL,
    request_digest character varying(64) NOT NULL,
    evidence text,
    occurred_at timestamp with time zone NOT NULL,
    row_digest character(64) NOT NULL,
    CONSTRAINT control_audit_chain_check CHECK (((chain_index >= 1) AND (previous_digest ~ '^[0-9a-f]{64}$'::text) AND (row_digest ~ '^[0-9a-f]{64}$'::text) AND ((chain_index > 1) OR (previous_digest = repeat('0'::text, 64))))),
    CONSTRAINT control_audit_evidence_check CHECK (((evidence IS NULL) OR (octet_length(evidence) <= 4096))),
    CONSTRAINT control_audit_operation_check CHECK (((operation)::text ~ '^[A-Z][A-Z0-9_]{0,39}$'::text))
);

ALTER TABLE ONLY public.control_audit FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_audit_anchors (
    tenant_id character varying(128) NOT NULL,
    chain_index bigint NOT NULL,
    head_digest character(64) NOT NULL,
    key_id character varying(128) NOT NULL,
    issued_at_unix_ms bigint NOT NULL,
    document text NOT NULL,
    signature text NOT NULL,
    sink character varying(64) NOT NULL,
    location character varying(512) NOT NULL,
    content_sha256 character(64) NOT NULL,
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT control_audit_anchors_check CHECK (((chain_index >= 1) AND (head_digest ~ '^[0-9a-f]{64}$'::text) AND (content_sha256 ~ '^[0-9a-f]{64}$'::text) AND ((issued_at_unix_ms >= 0) AND (issued_at_unix_ms <= '9007199254740991'::bigint)) AND (octet_length(document) <= 1024) AND ((octet_length(signature) >= 1) AND (octet_length(signature) <= 1024)) AND (length((key_id)::text) >= 1) AND (length((sink)::text) >= 1) AND (length((location)::text) >= 1)))
);

ALTER TABLE ONLY public.control_audit_anchors FORCE ROW LEVEL SECURITY;

ALTER TABLE public.control_audit ALTER COLUMN sequence ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.control_audit_sequence_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.control_authorization_outbox (
    tenant_id character varying(128) NOT NULL,
    revision bigint NOT NULL,
    authorization_epoch bigint NOT NULL,
    occurred_at bigint NOT NULL,
    published_at bigint
);

ALTER TABLE ONLY public.control_authorization_outbox FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_bootstrap_admins (
    tenant_id character varying(128) NOT NULL,
    user_id character varying(128) NOT NULL
);

ALTER TABLE ONLY public.control_bootstrap_admins FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_builder (
    tenant_id text NOT NULL,
    kind text NOT NULL,
    record_id text NOT NULL,
    revision bigint NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_builder_document_check CHECK ((octet_length(document) <= 65536)),
    CONSTRAINT control_builder_kind_check CHECK ((kind = ANY (ARRAY['DRAFT'::text, 'VERSION'::text, 'TEST'::text]))),
    CONSTRAINT control_builder_revision_check CHECK (((revision > 0) AND (revision <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_builder FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_business_key_history (
    tenant_id text NOT NULL,
    tool_id text NOT NULL,
    namespace text NOT NULL,
    namespace_identity text NOT NULL,
    business_key_hash text NOT NULL,
    invocation_id text NOT NULL,
    fingerprint text NOT NULL,
    caller_agent_id text NOT NULL,
    caller_user_id text,
    created_at bigint NOT NULL,
    dedupe_expires_at bigint NOT NULL,
    retain_until bigint NOT NULL,
    outcome text NOT NULL,
    tombstoned_at bigint,
    archived_at bigint NOT NULL,
    CONSTRAINT control_business_key_history_business_key_hash_check CHECK ((length(business_key_hash) = 64)),
    CONSTRAINT control_business_key_history_check CHECK ((dedupe_expires_at > created_at)),
    CONSTRAINT control_business_key_history_check1 CHECK ((retain_until >= dedupe_expires_at)),
    CONSTRAINT control_business_key_history_check2 CHECK (((archived_at >= dedupe_expires_at) AND (archived_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_business_key_history_check3 CHECK (((namespace = 'TENANT'::text) = (namespace_identity = ''::text))),
    CONSTRAINT control_business_key_history_created_at_check CHECK (((created_at >= 0) AND (created_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_business_key_history_fingerprint_check CHECK ((length(fingerprint) = 71)),
    CONSTRAINT control_business_key_history_namespace_check CHECK ((namespace = ANY (ARRAY['AGENT'::text, 'DELEGATED_USER'::text, 'TENANT'::text]))),
    CONSTRAINT control_business_key_history_outcome_check CHECK ((outcome = ANY (ARRAY['COMPLETED'::text, 'FAILED'::text, 'REJECTED'::text, 'EXPIRED'::text, 'CANCELLED'::text])))
);

ALTER TABLE ONLY public.control_business_key_history FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_business_keys (
    tenant_id character varying(128) NOT NULL,
    tool_id character varying(256) NOT NULL,
    namespace character varying(16) NOT NULL,
    namespace_identity character varying(128) NOT NULL,
    business_key_hash character(64) NOT NULL,
    source character varying(16) NOT NULL,
    fingerprint character(71) NOT NULL,
    caller_agent_id character varying(128) NOT NULL,
    caller_user_id character varying(128),
    invocation_id character varying(128) NOT NULL,
    created_at bigint NOT NULL,
    dedupe_expires_at bigint,
    retain_until bigint NOT NULL,
    outcome character varying(32),
    tombstoned_at bigint,
    CONSTRAINT control_business_keys_business_key_hash_check CHECK ((business_key_hash ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT control_business_keys_check CHECK ((((namespace)::text = 'TENANT'::text) = ((namespace_identity)::text = ''::text))),
    CONSTRAINT control_business_keys_check1 CHECK ((((source)::text = 'DEDUPE_WINDOW'::text) = (dedupe_expires_at IS NOT NULL))),
    CONSTRAINT control_business_keys_check2 CHECK (((dedupe_expires_at IS NULL) OR (dedupe_expires_at > created_at))),
    CONSTRAINT control_business_keys_check3 CHECK ((retain_until >= created_at)),
    CONSTRAINT control_business_keys_check4 CHECK (((tombstoned_at IS NULL) OR (outcome IS NOT NULL))),
    CONSTRAINT control_business_keys_created_at_check CHECK (((created_at >= 0) AND (created_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_business_keys_dedupe_expires_at_check CHECK (((dedupe_expires_at >= 0) AND (dedupe_expires_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_business_keys_fingerprint_check CHECK ((fingerprint ~ '^sha256:[a-f0-9]{64}$'::text)),
    CONSTRAINT control_business_keys_namespace_check CHECK (((namespace)::text = ANY ((ARRAY['AGENT'::character varying, 'DELEGATED_USER'::character varying, 'TENANT'::character varying])::text[]))),
    CONSTRAINT control_business_keys_outcome_check CHECK (((outcome)::text = ANY ((ARRAY['COMPLETED'::character varying, 'PARTIAL'::character varying, 'FAILED'::character varying, 'REJECTED'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying, 'OUTCOME_UNKNOWN'::character varying])::text[]))),
    CONSTRAINT control_business_keys_retain_until_check CHECK (((retain_until >= 0) AND (retain_until <= '9007199254740991'::bigint))),
    CONSTRAINT control_business_keys_source_check CHECK (((source)::text = ANY ((ARRAY['HEADER'::character varying, 'ARGUMENT'::character varying, 'DEDUPE_WINDOW'::character varying])::text[]))),
    CONSTRAINT control_business_keys_tombstoned_at_check CHECK (((tombstoned_at >= 0) AND (tombstoned_at <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_business_keys FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_configuration_changes (
    tenant_id character varying(128) NOT NULL,
    change_id character varying(128) NOT NULL,
    revision bigint NOT NULL,
    request_digest character(64) NOT NULL,
    document text NOT NULL
);

ALTER TABLE ONLY public.control_configuration_changes FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_console_devices (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    user_id text NOT NULL,
    device_id text NOT NULL,
    nonce_digest text NOT NULL,
    code_digest text NOT NULL,
    csr text NOT NULL,
    fingerprint text NOT NULL,
    expires_at bigint NOT NULL,
    verified_at bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY public.control_console_devices FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_credential_history (
    tenant_id character varying(128) NOT NULL,
    credential_sha256 character(64) NOT NULL
);

ALTER TABLE ONLY public.control_credential_history FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_effect_consent_approvals (
    tenant_id character varying(128) NOT NULL,
    consent_id character varying(128) NOT NULL,
    reviewer character varying(128) NOT NULL,
    authorization_epoch bigint NOT NULL,
    approved_at bigint NOT NULL,
    CONSTRAINT control_effect_consent_approvals_approved_at_check CHECK (((approved_at >= 0) AND (approved_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_effect_consent_approvals_authorization_epoch_check CHECK (((authorization_epoch >= 0) AND (authorization_epoch <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_effect_consent_approvals FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_effect_consent_revocations (
    tenant_id character varying(128) NOT NULL,
    consent_id character varying(128) NOT NULL,
    reviewer character varying(128) NOT NULL,
    revoked_at bigint NOT NULL,
    CONSTRAINT control_effect_consent_revocations_revoked_at_check CHECK (((revoked_at >= 0) AND (revoked_at <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_effect_consent_revocations FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_effect_consent_slots (
    tenant_id character varying(128) NOT NULL,
    consent_id character varying(128) NOT NULL,
    slot_index bigint NOT NULL,
    route_id character varying(128) NOT NULL,
    mapped_digest text NOT NULL,
    permitted_count bigint NOT NULL,
    CONSTRAINT control_effect_consent_slots_mapped_digest_check CHECK ((mapped_digest ~ '^sha256:[a-f0-9]{64}$'::text)),
    CONSTRAINT control_effect_consent_slots_permitted_count_check CHECK (((permitted_count >= 1) AND (permitted_count <= 64))),
    CONSTRAINT control_effect_consent_slots_slot_index_check CHECK (((slot_index >= 0) AND (slot_index <= 63)))
);

ALTER TABLE ONLY public.control_effect_consent_slots FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_effect_consent_uses (
    tenant_id character varying(128) NOT NULL,
    consent_id character varying(128) NOT NULL,
    slot_index bigint NOT NULL,
    effect_id character varying(128) NOT NULL,
    request_digest text NOT NULL,
    reserved_at bigint NOT NULL,
    CONSTRAINT control_effect_consent_uses_request_digest_check CHECK ((request_digest ~ '^sha256:[a-f0-9]{64}$'::text)),
    CONSTRAINT control_effect_consent_uses_reserved_at_check CHECK (((reserved_at >= 0) AND (reserved_at <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_effect_consent_uses FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_effect_consents (
    tenant_id character varying(128) NOT NULL,
    consent_id character varying(128) NOT NULL,
    operation_id character varying(128) NOT NULL,
    preview_digest text NOT NULL,
    document text NOT NULL,
    not_before bigint NOT NULL,
    expires_at bigint NOT NULL,
    CONSTRAINT control_effect_consents_check CHECK (((expires_at > not_before) AND (expires_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_effect_consents_document_check CHECK (((octet_length(document) <= 2097152) AND (jsonb_typeof((document)::jsonb) = 'object'::text))),
    CONSTRAINT control_effect_consents_not_before_check CHECK (((not_before >= 0) AND (not_before <= '9007199254740991'::bigint))),
    CONSTRAINT control_effect_consents_preview_digest_check CHECK ((preview_digest ~ '^sha256:[a-f0-9]{64}$'::text))
);

ALTER TABLE ONLY public.control_effect_consents FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_effect_evidence (
    tenant_id character varying(128) NOT NULL,
    effect_id character varying(128) NOT NULL,
    generation bigint NOT NULL,
    evidence_digest text NOT NULL,
    source text NOT NULL,
    outcome text NOT NULL,
    result_digest text NOT NULL,
    recorded_at bigint NOT NULL,
    CONSTRAINT control_effect_evidence_outcome_check CHECK ((outcome = ANY (ARRAY['COMPLETED'::text, 'FAILED'::text]))),
    CONSTRAINT control_effect_evidence_recorded_at_check CHECK ((recorded_at >= 0))
);

ALTER TABLE ONLY public.control_effect_evidence FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_effect_generations (
    tenant_id character varying(128) NOT NULL,
    effect_id character varying(128) NOT NULL,
    generation bigint NOT NULL,
    operation_id character varying(128) NOT NULL,
    attempt_id character varying(128) NOT NULL,
    auth_nonce character varying(128) NOT NULL,
    device_id character varying(128) NOT NULL,
    broker_id character varying(128) NOT NULL,
    handler_id character varying(128) NOT NULL,
    state text NOT NULL,
    issued_at bigint NOT NULL,
    expires_at bigint NOT NULL,
    outcome_deadline bigint NOT NULL,
    revision bigint NOT NULL,
    consumer text,
    claim_token text,
    CONSTRAINT control_effect_generations_check CHECK ((expires_at > issued_at)),
    CONSTRAINT control_effect_generations_check1 CHECK ((outcome_deadline >= expires_at)),
    CONSTRAINT control_effect_generations_check2 CHECK (((consumer IS NULL) = (claim_token IS NULL))),
    CONSTRAINT control_effect_generations_check3 CHECK (((state <> 'CONSUMED'::text) OR (consumer IS NOT NULL))),
    CONSTRAINT control_effect_generations_generation_check CHECK ((generation >= 1)),
    CONSTRAINT control_effect_generations_issued_at_check CHECK ((issued_at >= 0)),
    CONSTRAINT control_effect_generations_revision_check CHECK ((revision >= 1)),
    CONSTRAINT control_effect_generations_state_check CHECK ((state = ANY (ARRAY['AUTHORIZED'::text, 'RELEASED'::text, 'SENDING'::text, 'CONSUMED'::text, 'COMPLETED'::text, 'FAILED'::text, 'AMBIGUOUS'::text, 'CONFLICT'::text])))
);

ALTER TABLE ONLY public.control_effect_generations FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_effect_intents (
    tenant_id character varying(128) NOT NULL,
    effect_id character varying(128) NOT NULL,
    request_digest text NOT NULL,
    arguments_digest text NOT NULL,
    profile_digest text NOT NULL,
    classification text NOT NULL,
    document text NOT NULL,
    fenced_target_class text,
    authoritative_response boolean NOT NULL,
    recorded_at bigint NOT NULL,
    CONSTRAINT control_effect_intents_arguments_digest_check CHECK ((arguments_digest ~ '^sha256:[a-f0-9]{64}$'::text)),
    CONSTRAINT control_effect_intents_classification_check CHECK ((classification = ANY (ARRAY['READ'::text, 'WRITE'::text, 'DESTRUCTIVE'::text, 'FINANCIAL'::text]))),
    CONSTRAINT control_effect_intents_document_check CHECK (((octet_length(document) <= 1048576) AND (jsonb_typeof((document)::jsonb) = 'object'::text))),
    CONSTRAINT control_effect_intents_fenced_target_class_check CHECK (((fenced_target_class IS NULL) OR (fenced_target_class = ANY (ARRAY['SHARED_SQL'::text, 'SQL_WITH_EXTERNAL_ACTION'::text])))),
    CONSTRAINT control_effect_intents_profile_digest_check CHECK ((profile_digest ~ '^sha256:[a-f0-9]{64}$'::text)),
    CONSTRAINT control_effect_intents_recorded_at_check CHECK (((recorded_at >= 0) AND (recorded_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_effect_intents_request_digest_check CHECK ((request_digest ~ '^sha256:[a-f0-9]{64}$'::text))
);

ALTER TABLE ONLY public.control_effect_intents FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_effect_target_bindings (
    tenant_id character varying(128) NOT NULL,
    effect_id character varying(128) NOT NULL,
    operation_id character varying(128) NOT NULL,
    route_id character varying(128) NOT NULL,
    profile_digest text NOT NULL,
    guarantee_digest text NOT NULL,
    header text NOT NULL,
    target_key text NOT NULL,
    credential_version bigint NOT NULL,
    first_authorized_at bigint NOT NULL,
    valid_until bigint NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_effect_target_bindings_check CHECK (((valid_until > first_authorized_at) AND (valid_until <= '9007199254740991'::bigint))),
    CONSTRAINT control_effect_target_bindings_credential_version_check CHECK (((credential_version >= 0) AND (credential_version <= '9007199254740991'::bigint))),
    CONSTRAINT control_effect_target_bindings_document_check CHECK ((octet_length(document) <= 8192)),
    CONSTRAINT control_effect_target_bindings_first_authorized_at_check CHECK (((first_authorized_at >= 0) AND (first_authorized_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_effect_target_bindings_guarantee_digest_check CHECK ((length(guarantee_digest) = 71)),
    CONSTRAINT control_effect_target_bindings_header_check CHECK (((length(header) >= 1) AND (length(header) <= 64))),
    CONSTRAINT control_effect_target_bindings_profile_digest_check CHECK ((length(profile_digest) = 71)),
    CONSTRAINT control_effect_target_bindings_target_key_check CHECK ((length(target_key) = 67))
);

ALTER TABLE ONLY public.control_effect_target_bindings FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_effects (
    tenant_id character varying(128) NOT NULL,
    effect_id character varying(128) NOT NULL,
    operation_id character varying(128) NOT NULL,
    route_id text NOT NULL,
    request_digest text NOT NULL,
    current_generation bigint NOT NULL,
    CONSTRAINT control_effects_current_generation_check CHECK ((current_generation >= 1))
);

ALTER TABLE ONLY public.control_effects FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_endpoint_configurations (
    tenant_id character varying(128) NOT NULL,
    device_id character varying(128) NOT NULL,
    source_revision bigint NOT NULL,
    document text NOT NULL,
    acknowledged_digest character(64),
    local_tools text NOT NULL,
    CONSTRAINT control_endpoint_configurations_document_check CHECK ((octet_length(document) <= 98304)),
    CONSTRAINT control_endpoint_configurations_local_tools_check CHECK ((octet_length(local_tools) <= 49152)),
    CONSTRAINT control_endpoint_configurations_source_revision_check CHECK ((source_revision >= 0))
);

ALTER TABLE ONLY public.control_endpoint_configurations FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_endpoints (
    tenant_id character varying(128) NOT NULL,
    device_id character varying(128) NOT NULL,
    key_fingerprint character(64) NOT NULL,
    document text NOT NULL,
    csr text NOT NULL,
    report_digest character(64) NOT NULL,
    acknowledgment text NOT NULL,
    CONSTRAINT control_endpoints_acknowledgment_check CHECK ((octet_length(acknowledgment) <= 131072)),
    CONSTRAINT control_endpoints_check CHECK (((((document)::jsonb ->> 'deviceId'::text) = (device_id)::text) AND (((document)::jsonb ->> 'tenantId'::text) = (tenant_id)::text))),
    CONSTRAINT control_endpoints_csr_check CHECK ((octet_length(csr) <= 16384)),
    CONSTRAINT control_endpoints_document_check CHECK ((octet_length(document) <= 1048576))
);

ALTER TABLE ONLY public.control_endpoints FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_enrollments (
    tenant_id character varying(128) NOT NULL,
    enrollment_id character varying(128) NOT NULL,
    code_digest character(64) NOT NULL,
    device_digest character(64) NOT NULL,
    document text NOT NULL,
    csr text NOT NULL,
    user_id character varying(128),
    certificate text,
    expires_at bigint NOT NULL,
    last_poll bigint DEFAULT 0 NOT NULL,
    CONSTRAINT control_enrollments_certificate_check CHECK ((octet_length(certificate) <= 32768)),
    CONSTRAINT control_enrollments_csr_check CHECK ((octet_length(csr) <= 16384)),
    CONSTRAINT control_enrollments_document_check CHECK ((octet_length(document) <= 16384)),
    CONSTRAINT control_enrollments_expires_at_check CHECK ((expires_at >= 0)),
    CONSTRAINT control_enrollments_last_poll_check CHECK ((last_poll >= 0))
);

ALTER TABLE ONLY public.control_enrollments FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_enterprise_adoptions (
    tenant_id character varying(128) NOT NULL,
    actor_type character varying(16) NOT NULL,
    actor_id character varying(128) NOT NULL,
    directory_revision bigint NOT NULL,
    snapshot_sequence bigint NOT NULL,
    graph_digest character(64) NOT NULL,
    observed_at bigint NOT NULL,
    authorization_epoch bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY public.control_enterprise_adoptions FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_enterprise_approvals (
    tenant_id character varying(128) NOT NULL,
    approval_id character varying(128) NOT NULL,
    approval_type character varying(16) NOT NULL,
    invocation_id character varying(128) NOT NULL,
    request_digest character(64) NOT NULL,
    authorization_epoch bigint NOT NULL,
    expires_at bigint NOT NULL,
    revision bigint NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_enterprise_approvals_approval_type_check CHECK (((approval_type)::text = ANY ((ARRAY['OPERATION'::character varying, 'CONFIGURATION'::character varying])::text[])))
);

ALTER TABLE ONLY public.control_enterprise_approvals FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_enterprise_invocations (
    tenant_id character varying(128) NOT NULL,
    invocation_id character varying(128) NOT NULL,
    request_digest character(64) NOT NULL,
    state character varying(32) NOT NULL,
    authorization_epoch bigint NOT NULL,
    created_at bigint NOT NULL,
    expires_at bigint NOT NULL,
    revision bigint NOT NULL,
    document text NOT NULL,
    tool_id character varying(256),
    package_id character varying(128),
    package_digest character(71),
    pool_id character varying(128),
    runtime_digest character(71),
    effect_safety character varying(16),
    requires_execution_ledger boolean,
    lease_holder character varying(256),
    lease_expires_at bigint,
    cancel_requested_at bigint,
    not_started boolean DEFAULT false NOT NULL,
    task_id character varying(64),
    task_state character varying(32),
    task_owner_digest character(64),
    task_retain_until bigint,
    task_input_deadline_at bigint,
    task_last_polled_at bigint,
    task_document text,
    insights_fact_seq bigint DEFAULT 0 NOT NULL,
    CONSTRAINT control_enterprise_invocations_cancel_requested_at_check CHECK (((cancel_requested_at >= 0) AND (cancel_requested_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_enterprise_invocations_effect_safety_check CHECK (((effect_safety)::text = ANY ((ARRAY['READ_ONLY'::character varying, 'IDEMPOTENT'::character varying, 'NON_IDEMPOTENT'::character varying, 'UNKNOWN'::character varying])::text[]))),
    CONSTRAINT control_enterprise_invocations_insights_fact_seq_check CHECK ((insights_fact_seq >= 0)),
    CONSTRAINT control_enterprise_invocations_lease_expires_at_check CHECK (((lease_expires_at >= 0) AND (lease_expires_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_enterprise_invocations_package_digest_check CHECK ((package_digest ~ '^sha256:[a-f0-9]{64}$'::text)),
    CONSTRAINT control_enterprise_invocations_runtime_digest_check CHECK ((runtime_digest ~ '^sha256:[a-f0-9]{64}$'::text)),
    CONSTRAINT control_enterprise_invocations_task_document_check CHECK ((octet_length(task_document) <= 32768)),
    CONSTRAINT control_enterprise_invocations_task_id_check CHECK (((task_id)::text ~ '^[A-Za-z0-9_-]{22,64}$'::text)),
    CONSTRAINT control_enterprise_invocations_task_input_deadline_at_check CHECK (((task_input_deadline_at >= 0) AND (task_input_deadline_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_enterprise_invocations_task_last_polled_at_check CHECK (((task_last_polled_at >= 0) AND (task_last_polled_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_enterprise_invocations_task_owner_digest_check CHECK ((task_owner_digest ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT control_enterprise_invocations_task_retain_until_check CHECK (((task_retain_until >= 0) AND (task_retain_until <= '9007199254740991'::bigint))),
    CONSTRAINT control_invocation_cancel_shape CHECK (((((task_state)::text IS DISTINCT FROM 'CANCEL_REQUESTED'::text) OR (cancel_requested_at IS NOT NULL)) AND ((NOT not_started) OR ((state)::text = 'FAILED'::text)))),
    CONSTRAINT control_invocation_lease_shape CHECK ((((lease_holder IS NULL) = (lease_expires_at IS NULL)) AND ((lease_holder IS NULL) OR ((state)::text <> ALL ((ARRAY['PENDING_APPROVAL'::character varying, 'QUEUED'::character varying, 'RESERVED'::character varying, 'DISPATCHED'::character varying])::text[]))))),
    CONSTRAINT control_invocation_task_projection CHECK (((task_state IS NULL) OR (((state)::text = 'PENDING_APPROVAL'::text) AND ((task_state)::text = 'AWAITING_APPROVAL'::text)) OR (((state)::text = 'QUEUED'::text) AND ((task_state)::text = 'QUEUED'::text)) OR (((state)::text = ANY ((ARRAY['RESERVED'::character varying, 'DISPATCHED'::character varying])::text[])) AND ((task_state)::text = ANY ((ARRAY['DISPATCHED'::character varying, 'CANCEL_REQUESTED'::character varying])::text[]))) OR (((state)::text = 'EXECUTING'::text) AND ((task_state)::text = ANY ((ARRAY['RUNNING'::character varying, 'INPUT_REQUIRED'::character varying, 'CANCEL_REQUESTED'::character varying])::text[]))) OR (((state)::text = 'SUCCEEDED'::text) AND ((task_state)::text = 'COMPLETED'::text)) OR (((state)::text = ANY ((ARRAY['FAILED'::character varying, 'PARTIAL'::character varying])::text[])) AND ((task_state)::text = 'FAILED'::text)) OR (((state)::text = 'CANCELLED'::text) AND ((task_state)::text = ANY ((ARRAY['CANCELLED'::character varying, 'REJECTED'::character varying])::text[]))) OR (((state)::text = 'EXPIRED'::text) AND ((task_state)::text = 'EXPIRED'::text)) OR (((state)::text = 'OUTCOME_UNKNOWN'::text) AND ((task_state)::text = 'OUTCOME_UNKNOWN'::text)))),
    CONSTRAINT control_invocation_task_shape CHECK ((((task_id IS NULL) = (task_state IS NULL)) AND ((task_id IS NULL) = (task_owner_digest IS NULL)) AND ((task_id IS NULL) = (task_retain_until IS NULL)) AND ((task_id IS NULL) = (task_document IS NULL)) AND ((task_id IS NULL) OR (requires_execution_ledger IS NOT NULL)))),
    CONSTRAINT control_invocation_v2_class CHECK (((requires_execution_ledger IS NULL) OR ((tool_id IS NOT NULL) AND (package_digest IS NOT NULL) AND (effect_safety IS NOT NULL) AND (requires_execution_ledger = ((effect_safety)::text = ANY ((ARRAY['NON_IDEMPOTENT'::character varying, 'UNKNOWN'::character varying])::text[]))))))
);

ALTER TABLE ONLY public.control_enterprise_invocations FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_enterprise_nonces (
    tenant_id character varying(128) NOT NULL,
    nonce character varying(128) NOT NULL,
    invocation_id character varying(128) NOT NULL,
    evaluation_digest character(64) NOT NULL,
    authorization_epoch bigint NOT NULL,
    issued_at bigint NOT NULL,
    expires_at bigint NOT NULL,
    consumed_at bigint,
    purpose text DEFAULT 'LEGACY'::text NOT NULL,
    CONSTRAINT control_enterprise_nonces_purpose_check CHECK ((purpose = ANY (ARRAY['LEGACY'::text, 'EXECUTION'::text, 'SERVICE'::text])))
);

ALTER TABLE ONLY public.control_enterprise_nonces FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_execution_budgets (
    tenant_id character varying(128) NOT NULL,
    budget_id character varying(128) NOT NULL,
    invocation_id character varying(128) NOT NULL,
    created_at bigint NOT NULL
);

ALTER TABLE ONLY public.control_execution_budgets FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_execution_evidence (
    tenant_id character varying(128) NOT NULL,
    invocation_id character varying(128) NOT NULL,
    fencing_token character varying(128) NOT NULL,
    report_digest character(64) NOT NULL,
    document text NOT NULL,
    recorded_at bigint NOT NULL,
    qualified_report boolean DEFAULT false NOT NULL,
    CONSTRAINT control_execution_evidence_document_check CHECK ((octet_length(document) <= 5242880)),
    CONSTRAINT control_execution_evidence_recorded_at_check CHECK ((recorded_at >= 0)),
    CONSTRAINT control_execution_evidence_report_digest_check CHECK ((report_digest ~ '^[a-f0-9]{64}$'::text))
);

ALTER TABLE ONLY public.control_execution_evidence FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_execution_leases (
    tenant_id character varying(128) NOT NULL,
    invocation_id character varying(128) NOT NULL,
    fencing_token character varying(128) NOT NULL,
    device_id character varying(128) NOT NULL,
    expires_at bigint NOT NULL,
    CONSTRAINT control_execution_leases_expires_at_check CHECK (((expires_at >= 0) AND (expires_at <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_execution_leases FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_fleet (
    tenant_id text NOT NULL,
    kind text NOT NULL,
    record_id text NOT NULL,
    revision bigint NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_fleet_document_check CHECK ((octet_length(document) <= 131072)),
    CONSTRAINT control_fleet_kind_check CHECK ((kind = ANY (ARRAY['RELEASE'::text, 'DESIRED'::text, 'ROLLOUT'::text]))),
    CONSTRAINT control_fleet_revision_check CHECK (((revision > 0) AND (revision <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_fleet FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_idempotency (
    tenant_id character varying(128) NOT NULL,
    actor_id character(64) NOT NULL,
    key character varying(128) NOT NULL,
    request_digest character(64) NOT NULL,
    status integer NOT NULL,
    body text NOT NULL,
    revision bigint NOT NULL,
    expires_at timestamp with time zone DEFAULT (clock_timestamp() + '7 days'::interval) NOT NULL
);

ALTER TABLE ONLY public.control_idempotency FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_insights_admissions (
    tenant_id character varying(128) NOT NULL,
    bucket_start bigint NOT NULL,
    tool_id character varying(256) NOT NULL,
    category character varying(32) NOT NULL,
    count bigint NOT NULL,
    CONSTRAINT control_insights_admissions_count_check CHECK ((count >= 0))
);

ALTER TABLE ONLY public.control_insights_admissions FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_insights_attempts (
    tenant_id character varying(128) NOT NULL,
    invocation_id character varying(128) NOT NULL,
    attempt_number integer NOT NULL,
    execution_id character(35),
    executor_id character varying(128),
    started_at bigint,
    ended_at bigint,
    outcome character varying(32),
    duration_ms bigint,
    CONSTRAINT control_insights_attempts_attempt_number_check CHECK (((attempt_number >= 1) AND (attempt_number <= 64))),
    CONSTRAINT control_insights_attempts_execution_id_check CHECK ((execution_id ~ '^ex-[0-9a-f]{32}$'::text))
);

ALTER TABLE ONLY public.control_insights_attempts FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_insights_cells (
    tenant_id character varying(128) NOT NULL,
    series character varying(8) NOT NULL,
    granularity character varying(8) NOT NULL,
    bucket_start bigint NOT NULL,
    dim character varying(256) NOT NULL,
    outcome character varying(32) NOT NULL,
    calls bigint NOT NULL,
    duration_count bigint NOT NULL,
    duration_sum bigint NOT NULL,
    histogram text NOT NULL,
    CONSTRAINT control_insights_cells_calls_check CHECK ((calls >= 0)),
    CONSTRAINT control_insights_cells_duration_count_check CHECK ((duration_count >= 0)),
    CONSTRAINT control_insights_cells_duration_sum_check CHECK ((duration_sum >= 0)),
    CONSTRAINT control_insights_cells_granularity_check CHECK (((granularity)::text = ANY ((ARRAY['MINUTE'::character varying, 'HOUR'::character varying, 'DAY'::character varying])::text[]))),
    CONSTRAINT control_insights_cells_histogram_check CHECK ((octet_length(histogram) <= 1024)),
    CONSTRAINT control_insights_cells_series_check CHECK (((series)::text = ANY ((ARRAY['TOOL'::character varying, 'CLIENT'::character varying])::text[])))
);

ALTER TABLE ONLY public.control_insights_cells FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_insights_checkpoints (
    tenant_id character varying(128) NOT NULL,
    invocation_id character varying(128) NOT NULL,
    last_fact_seq bigint NOT NULL,
    contribution text NOT NULL,
    final_at bigint,
    CONSTRAINT control_insights_checkpoints_contribution_check CHECK ((octet_length(contribution) <= 8192)),
    CONSTRAINT control_insights_checkpoints_last_fact_seq_check CHECK ((last_fact_seq >= 0))
);

ALTER TABLE ONLY public.control_insights_checkpoints FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_insights_facts (
    tenant_id character varying(128) NOT NULL,
    invocation_id character varying(128) NOT NULL,
    fact_seq bigint NOT NULL,
    revision bigint NOT NULL,
    from_state character varying(32),
    to_state character varying(32) NOT NULL,
    occurred_at bigint NOT NULL,
    execution_id character(35),
    detail text NOT NULL,
    projected_at bigint,
    CONSTRAINT control_insights_facts_detail_check CHECK ((octet_length(detail) <= 8192)),
    CONSTRAINT control_insights_facts_execution_id_check CHECK ((execution_id ~ '^ex-[0-9a-f]{32}$'::text)),
    CONSTRAINT control_insights_facts_fact_seq_check CHECK (((fact_seq >= 1) AND (fact_seq <= '9007199254740991'::bigint))),
    CONSTRAINT control_insights_facts_occurred_at_check CHECK (((occurred_at >= 0) AND (occurred_at <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_insights_facts FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_insights_gateway_epochs (
    tenant_id character varying(128) NOT NULL,
    gateway_id character varying(128) NOT NULL,
    epoch character varying(128) NOT NULL,
    last_sequence bigint NOT NULL,
    missing bigint NOT NULL,
    dropped bigint NOT NULL,
    final boolean NOT NULL,
    last_seen bigint NOT NULL,
    CONSTRAINT control_insights_gateway_epochs_dropped_check CHECK ((dropped >= 0)),
    CONSTRAINT control_insights_gateway_epochs_last_sequence_check CHECK ((last_sequence >= 0)),
    CONSTRAINT control_insights_gateway_epochs_missing_check CHECK ((missing >= 0))
);

ALTER TABLE ONLY public.control_insights_gateway_epochs FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_insights_invocations (
    tenant_id character varying(128) NOT NULL,
    invocation_id character varying(128) NOT NULL,
    tool_id character varying(256) NOT NULL,
    action character varying(256) NOT NULL,
    client_id character varying(128),
    user_id character varying(128),
    agent_id character varying(128),
    tool_call_id character varying(128),
    session_id character varying(128),
    agent_run_id character varying(128),
    parent_invocation_id character varying(128),
    outcome character varying(32) NOT NULL,
    error_category character varying(32),
    attempts integer NOT NULL,
    accepted_at bigint NOT NULL,
    approval_requested_at bigint,
    approval_resolved_at bigint,
    queued_at bigint,
    first_started_at bigint,
    terminal_at bigint,
    approval_ms bigint,
    queue_ms bigint,
    execution_ms bigint,
    end_to_end_ms bigint,
    partial_coverage boolean NOT NULL,
    span_state character varying(16),
    CONSTRAINT control_insights_invocations_attempts_check CHECK (((attempts >= 0) AND (attempts <= 64))),
    CONSTRAINT control_insights_invocations_span_state_check CHECK (((span_state)::text = ANY ((ARRAY['RETAINED'::character varying, 'SAMPLED_OUT'::character varying, 'DROPPED'::character varying])::text[])))
);

ALTER TABLE ONLY public.control_insights_invocations FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_insights_spans (
    tenant_id character varying(128) NOT NULL,
    invocation_id character varying(128) NOT NULL,
    span_id character(16) NOT NULL,
    trace_id character(32) NOT NULL,
    parent_span_id character(16),
    name character varying(32) NOT NULL,
    source character varying(16) NOT NULL,
    execution_id character(35),
    start_at bigint NOT NULL,
    duration_ms bigint NOT NULL,
    status character varying(8) NOT NULL,
    route_id character varying(128),
    http_status integer,
    error_code character varying(64),
    stored_at bigint NOT NULL,
    CONSTRAINT control_insights_spans_duration_ms_check CHECK (((duration_ms >= 0) AND (duration_ms <= 86400000))),
    CONSTRAINT control_insights_spans_execution_id_check CHECK ((execution_id ~ '^ex-[0-9a-f]{32}$'::text)),
    CONSTRAINT control_insights_spans_http_status_check CHECK (((http_status >= 100) AND (http_status <= 599))),
    CONSTRAINT control_insights_spans_parent_span_id_check CHECK ((parent_span_id ~ '^[0-9a-f]{16}$'::text)),
    CONSTRAINT control_insights_spans_source_check CHECK (((source)::text = ANY ((ARRAY['GATEWAY'::character varying, 'TOOL_HOST'::character varying, 'CLIENT'::character varying])::text[]))),
    CONSTRAINT control_insights_spans_span_id_check CHECK ((span_id ~ '^[0-9a-f]{16}$'::text)),
    CONSTRAINT control_insights_spans_status_check CHECK (((status)::text = ANY ((ARRAY['OK'::character varying, 'ERROR'::character varying])::text[]))),
    CONSTRAINT control_insights_spans_trace_id_check CHECK ((trace_id ~ '^[0-9a-f]{32}$'::text))
);

ALTER TABLE ONLY public.control_insights_spans FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_insights_tenant_state (
    tenant_id character varying(128) NOT NULL,
    collecting_since bigint NOT NULL,
    summaries_suspended_since bigint,
    summary_rows bigint DEFAULT 0 NOT NULL,
    metrics_exported_through bigint,
    CONSTRAINT control_insights_tenant_state_summary_rows_check CHECK ((summary_rows >= 0))
);

ALTER TABLE ONLY public.control_insights_tenant_state FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_invocation_authority (
    tenant_id character varying(128) NOT NULL,
    invocation_id character varying(128) NOT NULL,
    expires_at bigint NOT NULL
);

ALTER TABLE ONLY public.control_invocation_authority FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_invocation_event_streams (
    tenant_id character varying(128) NOT NULL,
    invocation_id character varying(128) NOT NULL,
    last_cursor bigint NOT NULL,
    event_count integer NOT NULL,
    byte_count bigint NOT NULL,
    dropped_frames bigint NOT NULL,
    refused_frames bigint NOT NULL,
    retain_until bigint NOT NULL,
    purged_at bigint,
    CONSTRAINT control_invocation_event_streams_byte_count_check CHECK ((byte_count >= 0)),
    CONSTRAINT control_invocation_event_streams_dropped_frames_check CHECK ((dropped_frames >= 0)),
    CONSTRAINT control_invocation_event_streams_event_count_check CHECK ((event_count >= 0)),
    CONSTRAINT control_invocation_event_streams_last_cursor_check CHECK (((last_cursor >= 0) AND (last_cursor <= '9007199254740991'::bigint))),
    CONSTRAINT control_invocation_event_streams_purged_at_check CHECK (((purged_at >= 0) AND (purged_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_invocation_event_streams_refused_frames_check CHECK ((refused_frames >= 0)),
    CONSTRAINT control_invocation_event_streams_retain_until_check CHECK (((retain_until >= 0) AND (retain_until <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_invocation_event_streams FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_invocation_events (
    tenant_id character varying(128) NOT NULL,
    invocation_id character varying(128) NOT NULL,
    event_cursor bigint NOT NULL,
    source character varying(16) NOT NULL,
    kind character varying(16) NOT NULL,
    attempt integer,
    fencing_token character varying(128),
    sequence integer,
    recorded_at bigint NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_invocation_events_attempt_check CHECK (((attempt >= 1) AND (attempt <= 16))),
    CONSTRAINT control_invocation_events_check CHECK ((((source)::text = 'EXECUTOR'::text) = ((fencing_token IS NOT NULL) AND (attempt IS NOT NULL)))),
    CONSTRAINT control_invocation_events_check1 CHECK (((sequence IS NULL) OR ((source)::text = 'EXECUTOR'::text))),
    CONSTRAINT control_invocation_events_document_check CHECK ((octet_length(document) <= 131072)),
    CONSTRAINT control_invocation_events_event_cursor_check CHECK (((event_cursor >= 1) AND (event_cursor <= '9007199254740991'::bigint))),
    CONSTRAINT control_invocation_events_kind_check CHECK (((kind)::text = ANY ((ARRAY['LOG'::character varying, 'PROGRESS'::character varying, 'INPUT_REQUEST'::character varying, 'STATE'::character varying, 'RESULT'::character varying])::text[]))),
    CONSTRAINT control_invocation_events_recorded_at_check CHECK (((recorded_at >= 0) AND (recorded_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_invocation_events_sequence_check CHECK (((sequence >= 1) AND (sequence <= 2147483647))),
    CONSTRAINT control_invocation_events_source_check CHECK (((source)::text = ANY ((ARRAY['EXECUTOR'::character varying, 'PLATFORM'::character varying])::text[])))
);

ALTER TABLE ONLY public.control_invocation_events FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_invocation_results (
    tenant_id character varying(128) NOT NULL,
    invocation_id character varying(128) NOT NULL,
    fencing_nonce character varying(128) NOT NULL,
    result_digest character(71) NOT NULL,
    byte_length integer NOT NULL,
    payload bytea,
    created_at bigint NOT NULL,
    retain_until bigint NOT NULL,
    purged_at bigint,
    CONSTRAINT control_invocation_results_byte_length_check CHECK (((byte_length >= 0) AND (byte_length <= 4194304))),
    CONSTRAINT control_invocation_results_check CHECK (((payload IS NULL) = (purged_at IS NOT NULL))),
    CONSTRAINT control_invocation_results_check1 CHECK (((payload IS NULL) OR (octet_length(payload) = byte_length))),
    CONSTRAINT control_invocation_results_check2 CHECK ((retain_until >= created_at)),
    CONSTRAINT control_invocation_results_created_at_check CHECK (((created_at >= 0) AND (created_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_invocation_results_purged_at_check CHECK (((purged_at >= 0) AND (purged_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_invocation_results_result_digest_check CHECK ((result_digest ~ '^sha256:[a-f0-9]{64}$'::text)),
    CONSTRAINT control_invocation_results_retain_until_check CHECK (((retain_until >= 0) AND (retain_until <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_invocation_results FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_mcp_approvals (
    tenant_id character varying(128) NOT NULL,
    slug character varying(31) NOT NULL,
    sequence bigint NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_mcp_approvals_document_check CHECK ((octet_length(document) <= 1048576)),
    CONSTRAINT control_mcp_approvals_sequence_check CHECK (((sequence >= 1) AND (sequence <= '9007199254740991'::bigint))),
    CONSTRAINT control_mcp_approvals_slug_check CHECK (((slug)::text ~ '^[a-z][a-z0-9-]{1,30}$'::text))
);

ALTER TABLE ONLY public.control_mcp_approvals FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_mcp_requests (
    tenant_id character varying(128) NOT NULL,
    request_id character varying(128) NOT NULL,
    device_id character varying(128) NOT NULL,
    state character varying(32) NOT NULL,
    received_at bigint NOT NULL,
    expires_at bigint NOT NULL,
    record text NOT NULL,
    task text NOT NULL,
    result text,
    CONSTRAINT control_mcp_requests_record_check CHECK ((octet_length(record) <= 8192)),
    CONSTRAINT control_mcp_requests_result_check CHECK ((octet_length(result) <= 65536)),
    CONSTRAINT control_mcp_requests_state_check CHECK (((state)::text = ANY ((ARRAY['RECEIVED'::character varying, 'WAITING_FOR_POLL'::character varying, 'SUBMITTED'::character varying, 'RESPONSE_RECEIVED'::character varying, 'DONE'::character varying, 'FAILED'::character varying, 'EXPIRED'::character varying])::text[]))),
    CONSTRAINT control_mcp_requests_task_check CHECK ((octet_length(task) <= 16384))
);

ALTER TABLE ONLY public.control_mcp_requests FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_mcp_scans (
    tenant_id character varying(128) NOT NULL,
    scan_id character varying(128) NOT NULL,
    slug character varying(31) NOT NULL,
    host_id character varying(128) NOT NULL,
    state character varying(16) NOT NULL,
    deadline bigint NOT NULL,
    created_at bigint NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_mcp_scans_created_at_check CHECK (((created_at >= 0) AND (created_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_mcp_scans_deadline_check CHECK (((deadline >= 0) AND (deadline <= '9007199254740991'::bigint))),
    CONSTRAINT control_mcp_scans_document_check CHECK ((octet_length(document) <= 65536)),
    CONSTRAINT control_mcp_scans_slug_check CHECK (((slug)::text ~ '^[a-z][a-z0-9-]{1,30}$'::text)),
    CONSTRAINT control_mcp_scans_state_check CHECK (((state)::text = ANY ((ARRAY['QUEUED'::character varying, 'CLAIMED'::character varying, 'ADMITTED'::character varying, 'FINISHED'::character varying, 'FAILED'::character varying, 'EXPIRED'::character varying])::text[])))
);

ALTER TABLE ONLY public.control_mcp_scans FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_mcp_servers (
    tenant_id character varying(128) NOT NULL,
    slug character varying(31) NOT NULL,
    revision bigint NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_mcp_servers_document_check CHECK ((octet_length(document) <= 65536)),
    CONSTRAINT control_mcp_servers_revision_check CHECK (((revision >= 1) AND (revision <= '9007199254740991'::bigint))),
    CONSTRAINT control_mcp_servers_slug_check CHECK (((slug)::text ~ '^[a-z][a-z0-9-]{1,30}$'::text))
);

ALTER TABLE ONLY public.control_mcp_servers FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_mcp_snapshots (
    tenant_id character varying(128) NOT NULL,
    slug character varying(31) NOT NULL,
    revision bigint NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_mcp_snapshots_document_check CHECK ((octet_length(document) <= 8388608)),
    CONSTRAINT control_mcp_snapshots_revision_check CHECK (((revision >= 1) AND (revision <= '9007199254740991'::bigint))),
    CONSTRAINT control_mcp_snapshots_slug_check CHECK (((slug)::text ~ '^[a-z][a-z0-9-]{1,30}$'::text))
);

ALTER TABLE ONLY public.control_mcp_snapshots FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_operation_attempts (
    tenant_id character varying(128) NOT NULL,
    attempt_id character varying(128) NOT NULL,
    operation_id character varying(128) NOT NULL,
    evaluation_digest text NOT NULL,
    authorization_epoch bigint NOT NULL,
    issued_at bigint NOT NULL,
    expires_at bigint NOT NULL,
    CONSTRAINT control_operation_attempts_check CHECK ((expires_at > issued_at)),
    CONSTRAINT control_operation_attempts_issued_at_check CHECK ((issued_at >= 0))
);

ALTER TABLE ONLY public.control_operation_attempts FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_operation_cancellations (
    tenant_id character varying(128) NOT NULL,
    invocation_id character varying(128) NOT NULL
);

ALTER TABLE ONLY public.control_operation_cancellations FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_operation_outcomes (
    tenant_id character varying(128) NOT NULL,
    operation_id character varying(128) NOT NULL,
    revision bigint NOT NULL,
    invocation_revision bigint NOT NULL,
    state text NOT NULL,
    source_digest text NOT NULL,
    recorded_at bigint NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_operation_outcomes_document_check CHECK (((octet_length(document) <= 1048576) AND (jsonb_typeof((document)::jsonb) = 'object'::text))),
    CONSTRAINT control_operation_outcomes_invocation_revision_check CHECK ((invocation_revision >= 1)),
    CONSTRAINT control_operation_outcomes_recorded_at_check CHECK (((recorded_at >= 0) AND (recorded_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_operation_outcomes_revision_check CHECK ((revision >= 1)),
    CONSTRAINT control_operation_outcomes_source_digest_check CHECK ((source_digest ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT control_operation_outcomes_state_check CHECK ((state = ANY (ARRAY['RUNNING'::text, 'OUTCOME_UNKNOWN'::text, 'RECONCILIATION_REQUIRED'::text, 'SUCCEEDED'::text, 'PARTIAL'::text, 'FAILED'::text])))
);

ALTER TABLE ONLY public.control_operation_outcomes FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_operation_recoveries (
    tenant_id text NOT NULL,
    operation_id text NOT NULL,
    from_revision bigint NOT NULL,
    to_revision bigint NOT NULL,
    operation_revision bigint NOT NULL,
    previous_nonce text,
    original_deadline bigint NOT NULL,
    proof text NOT NULL,
    attempt_ids text NOT NULL,
    created_at bigint NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_operation_recoveries_attempt_ids_check CHECK (((octet_length(attempt_ids) <= 32768) AND (jsonb_typeof((attempt_ids)::jsonb) = 'array'::text))),
    CONSTRAINT control_operation_recoveries_check CHECK ((to_revision = (from_revision + 1))),
    CONSTRAINT control_operation_recoveries_check1 CHECK ((original_deadline > created_at)),
    CONSTRAINT control_operation_recoveries_created_at_check CHECK (((created_at >= 0) AND (created_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_operation_recoveries_document_check CHECK ((octet_length(document) <= 33554432)),
    CONSTRAINT control_operation_recoveries_from_revision_check CHECK ((from_revision >= 1)),
    CONSTRAINT control_operation_recoveries_operation_revision_check CHECK ((operation_revision >= 1)),
    CONSTRAINT control_operation_recoveries_proof_check CHECK ((proof = ANY (ARRAY['NEVER_CONSUMED'::text, 'QUALIFIED_BROKER_NO_WRITE'::text, 'TARGET_DEDUPLICATION'::text])))
);

ALTER TABLE ONLY public.control_operation_recoveries FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_operation_review_owners (
    tenant_id character varying(128) NOT NULL,
    operation_id character varying(128) NOT NULL,
    directory_revision bigint NOT NULL,
    source_digest text NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_operation_review_owners_directory_revision_check CHECK ((directory_revision >= 0)),
    CONSTRAINT control_operation_review_owners_document_check CHECK (((octet_length(document) <= 16384) AND (jsonb_typeof((document)::jsonb) = 'object'::text))),
    CONSTRAINT control_operation_review_owners_source_digest_check CHECK ((source_digest ~ '^[a-f0-9]{64}$'::text))
);

ALTER TABLE ONLY public.control_operation_review_owners FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_operations (
    tenant_id character varying(128) NOT NULL,
    operation_id character varying(128) NOT NULL,
    request_digest text NOT NULL,
    state text NOT NULL,
    legacy boolean NOT NULL,
    revision bigint NOT NULL,
    created_at bigint NOT NULL,
    CONSTRAINT control_operations_created_at_check CHECK ((created_at >= 0)),
    CONSTRAINT control_operations_revision_check CHECK ((revision >= 1)),
    CONSTRAINT control_operations_state_check CHECK ((state = ANY (ARRAY['RUNNING'::text, 'OUTCOME_UNKNOWN'::text, 'FAILED'::text])))
);

ALTER TABLE ONLY public.control_operations FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_outcome_reconciliations (
    tenant_id character varying(128) NOT NULL,
    reconciliation_id character varying(128) NOT NULL,
    revision bigint NOT NULL,
    document text NOT NULL
);

ALTER TABLE ONLY public.control_outcome_reconciliations FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_package_provisioning (
    tenant_id character varying(128) NOT NULL,
    profile_id character varying(128) NOT NULL,
    generation bigint NOT NULL,
    profile_digest character(71) NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_package_provisioning_document_check CHECK ((octet_length(document) <= 262144)),
    CONSTRAINT control_package_provisioning_generation_check CHECK (((generation >= 1) AND (generation <= '9007199254740991'::bigint))),
    CONSTRAINT control_package_provisioning_profile_digest_check CHECK ((profile_digest ~ '^sha256:[a-f0-9]{64}$'::text))
);

ALTER TABLE ONLY public.control_package_provisioning FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_policy_bundles (
    tenant_id character varying(128) NOT NULL,
    sequence bigint NOT NULL,
    directory_revision bigint NOT NULL,
    document text NOT NULL,
    policy text NOT NULL,
    published_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT control_policy_bundles_directory_revision_check CHECK (((directory_revision >= 0) AND (directory_revision <= '9007199254740991'::bigint))),
    CONSTRAINT control_policy_bundles_document_check CHECK ((octet_length(document) <= 2000100)),
    CONSTRAINT control_policy_bundles_policy_check CHECK ((octet_length(policy) <= 1048576)),
    CONSTRAINT control_policy_bundles_sequence_check CHECK (((sequence >= 1) AND (sequence <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_policy_bundles FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_queued_authority (
    tenant_id character varying(128) NOT NULL,
    kind character varying(32) NOT NULL,
    record_id character varying(128) NOT NULL,
    creator_user_id character varying(128) NOT NULL
);

ALTER TABLE ONLY public.control_queued_authority FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_record_ids (
    tenant_id character varying(128) NOT NULL,
    kind character varying(32) NOT NULL,
    record_id character varying(128) NOT NULL
);

ALTER TABLE ONLY public.control_record_ids FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_records (
    tenant_id character varying(128) NOT NULL,
    kind character varying(32) NOT NULL,
    record_id character varying(128) NOT NULL,
    revision bigint NOT NULL,
    document jsonb NOT NULL,
    CONSTRAINT control_records_check CHECK ((((document ->> 'id'::text) = (record_id)::text) AND (((document ->> 'revision'::text))::bigint = revision))),
    CONSTRAINT control_records_document_check CHECK ((jsonb_typeof(document) = 'object'::text)),
    CONSTRAINT control_records_kind_check CHECK (((kind)::text = ANY ((ARRAY['USER'::character varying, 'TEAM'::character varying, 'AGENT'::character varying, 'TOOL'::character varying, 'POLICY'::character varying, 'DEVICE'::character varying, 'ROLE'::character varying, 'DEVICE_GROUP'::character varying, 'AGENT_GROUP'::character varying, 'TOOL_GROUP'::character varying, 'GRANT'::character varying, 'DELEGATION'::character varying, 'AGENT_DELEGATION'::character varying, 'BINDING'::character varying, 'EXTRACTOR'::character varying, 'WORKLOAD_BINDING'::character varying, 'IDENTITY_BINDING'::character varying, 'DEVICE_EVIDENCE'::character varying])::text[]))),
    CONSTRAINT control_records_revision_check CHECK (((revision >= 1) AND (revision <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_records FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_recovery_floors (
    tenant_id character varying(128) NOT NULL,
    snapshot_sequence bigint NOT NULL,
    CONSTRAINT control_recovery_floors_snapshot_sequence_check CHECK ((snapshot_sequence >= 0))
);

ALTER TABLE ONLY public.control_recovery_floors FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_reviewed_recovery (
    tenant_id character varying(128) NOT NULL,
    review_digest character(64) NOT NULL
);

ALTER TABLE ONLY public.control_reviewed_recovery FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_secrets (
    tenant_id character varying(128) NOT NULL,
    name character varying(128) NOT NULL,
    tool_group_id character varying(128) NOT NULL,
    device_group_id character varying(128) NOT NULL,
    version bigint NOT NULL,
    key_version bigint NOT NULL,
    cipher text NOT NULL,
    CONSTRAINT control_secrets_cipher_check CHECK ((octet_length(cipher) <= 65536)),
    CONSTRAINT control_secrets_version_check CHECK (((version >= 1) AND (version <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_secrets FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_server_deployments (
    tenant_id character varying(128) NOT NULL,
    package_id character varying(127) NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_server_deployments_document_check CHECK ((octet_length(document) <= 65536))
);

ALTER TABLE ONLY public.control_server_deployments FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_server_documents (
    tenant_id character varying(128) NOT NULL,
    digest character(71) NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_server_documents_digest_check CHECK ((digest ~ '^sha256:[a-f0-9]{64}$'::text)),
    CONSTRAINT control_server_documents_document_check CHECK ((octet_length(document) <= 1048576))
);

ALTER TABLE ONLY public.control_server_documents FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_server_packages (
    tenant_id character varying(128) NOT NULL,
    package_digest character(71) NOT NULL,
    package_id character varying(127) NOT NULL,
    version character varying(128) NOT NULL,
    uploaded_at bigint NOT NULL,
    document text NOT NULL,
    content bytea NOT NULL,
    CONSTRAINT control_server_packages_content_check CHECK ((octet_length(content) <= 67108864)),
    CONSTRAINT control_server_packages_document_check CHECK ((octet_length(document) <= 4194304)),
    CONSTRAINT control_server_packages_package_digest_check CHECK ((package_digest ~ '^sha256:[a-f0-9]{64}$'::text)),
    CONSTRAINT control_server_packages_uploaded_at_check CHECK (((uploaded_at >= 0) AND (uploaded_at <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_server_packages FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_server_settings (
    tenant_id character varying(128) NOT NULL,
    revision bigint NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_server_settings_document_check CHECK ((octet_length(document) <= 16384)),
    CONSTRAINT control_server_settings_revision_check CHECK (((revision >= 1) AND (revision <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_server_settings FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_tenant_authority (
    tenant_id character varying(128) NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    authorization_epoch bigint DEFAULT 0 NOT NULL,
    directory_cache_generation bigint DEFAULT 0 NOT NULL,
    CONSTRAINT control_tenant_authority_authorization_epoch_check CHECK (((authorization_epoch >= 0) AND (authorization_epoch <= '9007199254740991'::bigint))),
    CONSTRAINT control_tenant_authority_directory_cache_generation_check CHECK ((directory_cache_generation >= 0)),
    CONSTRAINT control_tenant_authority_revision_check CHECK (((revision >= 0) AND (revision <= '9007199254740991'::bigint)))
);

ALTER TABLE ONLY public.control_tenant_authority FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_tenant_capacity (
    tenant_id character varying(128) NOT NULL,
    stored_bytes bigint DEFAULT 0 NOT NULL,
    stored_rows bigint DEFAULT 0 NOT NULL,
    queued_rows bigint DEFAULT 0 NOT NULL,
    max_stored_bytes bigint DEFAULT 1073741824 NOT NULL,
    max_stored_rows bigint DEFAULT 100000 NOT NULL,
    max_queued_rows bigint DEFAULT 256 NOT NULL,
    CONSTRAINT control_tenant_capacity_check CHECK (((stored_rows <= max_stored_rows) AND (queued_rows <= max_queued_rows) AND ((stored_bytes + (queued_rows * 16777216)) <= max_stored_bytes))),
    CONSTRAINT control_tenant_capacity_max_queued_rows_check CHECK (((max_queued_rows >= 1) AND (max_queued_rows <= 100000))),
    CONSTRAINT control_tenant_capacity_max_stored_bytes_check CHECK (((max_stored_bytes >= 4096) AND (max_stored_bytes <= '1099511627776'::bigint))),
    CONSTRAINT control_tenant_capacity_max_stored_rows_check CHECK (((max_stored_rows >= 1) AND (max_stored_rows <= 1000000))),
    CONSTRAINT control_tenant_capacity_queued_rows_check CHECK ((queued_rows >= 0)),
    CONSTRAINT control_tenant_capacity_stored_bytes_check CHECK ((stored_bytes >= 0)),
    CONSTRAINT control_tenant_capacity_stored_rows_check CHECK ((stored_rows >= 0))
);

ALTER TABLE ONLY public.control_tenant_capacity FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_tenant_hosts (
    hostname character varying(253) NOT NULL,
    tenant_id character varying(128) NOT NULL,
    CONSTRAINT control_tenant_hosts_hostname_check CHECK ((((hostname)::text = lower((hostname)::text)) AND (length((hostname)::text) > 0)))
);

CREATE TABLE public.control_tenant_identity_keys (
    tenant_id character varying(128) NOT NULL,
    purpose character varying(16) NOT NULL,
    key_id character varying(128) NOT NULL,
    state character varying(16) NOT NULL,
    provider_reference character varying(512) NOT NULL,
    public_material text NOT NULL,
    key_fingerprint character varying(64) NOT NULL,
    generation bigint NOT NULL,
    CONSTRAINT control_tenant_identity_keys_generation_check CHECK (((generation >= 1) AND (generation <= '9007199254740991'::bigint))),
    CONSTRAINT control_tenant_identity_keys_key_fingerprint_check CHECK (((key_fingerprint)::text ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT control_tenant_identity_keys_key_id_check CHECK (((length((key_id)::text) >= 1) AND (length((key_id)::text) <= 128))),
    CONSTRAINT control_tenant_identity_keys_public_material_check CHECK ((octet_length(public_material) <= 16384)),
    CONSTRAINT control_tenant_identity_keys_purpose_check CHECK (((purpose)::text = ANY ((ARRAY['DISCOVERY'::character varying, 'DEVICE_CA'::character varying])::text[]))),
    CONSTRAINT control_tenant_identity_keys_state_check CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'READ_ONLY'::character varying, 'RETIRED'::character varying])::text[])))
);

ALTER TABLE ONLY public.control_tenant_identity_keys FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_tenant_initializations (
    tenant_id character varying(128) NOT NULL,
    version integer NOT NULL,
    effective_digest character(64) NOT NULL,
    provenance text NOT NULL,
    completed_at bigint NOT NULL,
    CONSTRAINT control_tenant_initializations_completed_at_check CHECK ((completed_at >= 0)),
    CONSTRAINT control_tenant_initializations_effective_digest_check CHECK ((effective_digest ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT control_tenant_initializations_provenance_check CHECK ((octet_length(provenance) <= 65536)),
    CONSTRAINT control_tenant_initializations_version_check CHECK ((version = 1))
);

ALTER TABLE ONLY public.control_tenant_initializations FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_tenant_runtime_profiles (
    tenant_id character varying(128) NOT NULL,
    server_id character varying(128) NOT NULL,
    organization text NOT NULL,
    control_url text NOT NULL,
    gateway_url text NOT NULL,
    CONSTRAINT control_tenant_runtime_profiles_control_url_check CHECK ((octet_length(control_url) <= 2048)),
    CONSTRAINT control_tenant_runtime_profiles_gateway_url_check CHECK ((octet_length(gateway_url) <= 2048)),
    CONSTRAINT control_tenant_runtime_profiles_organization_check CHECK (((length(organization) >= 1) AND (length(organization) <= 256)))
);

ALTER TABLE ONLY public.control_tenant_runtime_profiles FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_tenant_vault_keys (
    tenant_id character varying(128) NOT NULL,
    version bigint NOT NULL,
    state character varying(16) NOT NULL,
    provider_reference character varying(128) NOT NULL,
    wrapped_encryption_key text NOT NULL,
    wrapped_retry_key text NOT NULL,
    CONSTRAINT control_tenant_vault_keys_state_check CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'READ_ONLY'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT control_tenant_vault_keys_version_check CHECK (((version >= 1) AND (version <= 1024))),
    CONSTRAINT control_tenant_vault_keys_wrapped_encryption_key_check CHECK ((octet_length(wrapped_encryption_key) <= 1024)),
    CONSTRAINT control_tenant_vault_keys_wrapped_retry_key_check CHECK ((octet_length(wrapped_retry_key) <= 1024))
);

ALTER TABLE ONLY public.control_tenant_vault_keys FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_tenants (
    tenant_id character varying(128) NOT NULL,
    alias character varying(63) NOT NULL,
    display_name text NOT NULL,
    status character varying(16) NOT NULL,
    created_at bigint NOT NULL,
    updated_at bigint NOT NULL,
    initialization_failure_code character varying(32),
    initialization_failed_at bigint,
    CONSTRAINT control_tenants_alias_check CHECK (((alias)::text ~ '^[a-z0-9][a-z0-9-]{0,62}$'::text)),
    CONSTRAINT control_tenants_check CHECK ((updated_at >= created_at)),
    CONSTRAINT control_tenants_check1 CHECK (((initialization_failure_code IS NULL) = (initialization_failed_at IS NULL))),
    CONSTRAINT control_tenants_check2 CHECK ((((status)::text = 'PROVISIONING'::text) OR (initialization_failure_code IS NULL))),
    CONSTRAINT control_tenants_created_at_check CHECK ((created_at >= 0)),
    CONSTRAINT control_tenants_display_name_check CHECK (((length(display_name) >= 1) AND (length(display_name) <= 256))),
    CONSTRAINT control_tenants_initialization_failed_at_check CHECK ((initialization_failed_at >= 0)),
    CONSTRAINT control_tenants_initialization_failure_code_check CHECK (((initialization_failure_code)::text = 'INITIALIZATION_FAILED'::text)),
    CONSTRAINT control_tenants_status_check CHECK (((status)::text = ANY ((ARRAY['PROVISIONING'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying])::text[]))),
    CONSTRAINT control_tenants_tenant_id_check CHECK (((tenant_id)::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'::text))
);

CREATE TABLE public.control_tool_host_dispatches (
    tenant_id character varying(128) NOT NULL,
    invocation_id character varying(128) NOT NULL,
    host_id character varying(128) NOT NULL,
    state character varying(16) NOT NULL,
    expires_at bigint NOT NULL,
    created_at bigint NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_tool_host_dispatches_created_at_check CHECK (((created_at >= 0) AND (created_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_tool_host_dispatches_document_check CHECK ((octet_length(document) <= 16777216)),
    CONSTRAINT control_tool_host_dispatches_expires_at_check CHECK (((expires_at >= 0) AND (expires_at <= '9007199254740991'::bigint))),
    CONSTRAINT control_tool_host_dispatches_state_check CHECK (((state)::text = ANY ((ARRAY['QUEUED'::character varying, 'CLAIMED'::character varying, 'ADMITTED'::character varying, 'REFUSED'::character varying, 'FINISHED'::character varying, 'EXPIRED'::character varying])::text[])))
);

ALTER TABLE ONLY public.control_tool_host_dispatches FORCE ROW LEVEL SECURITY;

CREATE TABLE public.control_tool_hosts (
    tenant_id character varying(128) NOT NULL,
    device_id character varying(128) NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_tool_hosts_document_check CHECK ((octet_length(document) <= 16384))
);

ALTER TABLE ONLY public.control_tool_hosts FORCE ROW LEVEL SECURITY;

ALTER TABLE ONLY public.control_approval_clocks
    ADD CONSTRAINT control_approval_clocks_pkey PRIMARY KEY (tenant_id);

ALTER TABLE ONLY public.control_attempt_effects
    ADD CONSTRAINT control_attempt_effects_pkey PRIMARY KEY (tenant_id, operation_id, attempt_id, effect_id);

ALTER TABLE ONLY public.control_attempt_manifest_seals
    ADD CONSTRAINT control_attempt_manifest_seals_pkey PRIMARY KEY (tenant_id, operation_id, attempt_id);

ALTER TABLE ONLY public.control_attempt_recovery_scopes
    ADD CONSTRAINT control_attempt_recovery_scopes_pkey PRIMARY KEY (tenant_id, attempt_id);

ALTER TABLE ONLY public.control_attempt_reviews
    ADD CONSTRAINT control_attempt_reviews_pkey PRIMARY KEY (tenant_id, operation_id, attempt_id);

ALTER TABLE ONLY public.control_attempt_settlements
    ADD CONSTRAINT control_attempt_settlements_pkey PRIMARY KEY (tenant_id, operation_id, attempt_id);

ALTER TABLE ONLY public.control_attempt_snapshots
    ADD CONSTRAINT control_attempt_snapshots_pkey PRIMARY KEY (tenant_id, attempt_id, revision);

ALTER TABLE ONLY public.control_audit
    ADD CONSTRAINT control_audit_pkey PRIMARY KEY (sequence);

ALTER TABLE ONLY public.control_audit
    ADD CONSTRAINT control_audit_chain_unique UNIQUE (tenant_id, chain_index);

ALTER TABLE ONLY public.control_audit_anchors
    ADD CONSTRAINT control_audit_anchors_pkey PRIMARY KEY (tenant_id, chain_index);

ALTER TABLE ONLY public.control_authorization_outbox
    ADD CONSTRAINT control_authorization_outbox_pkey PRIMARY KEY (tenant_id, revision);

ALTER TABLE ONLY public.control_bootstrap_admins
    ADD CONSTRAINT control_bootstrap_admins_pkey PRIMARY KEY (tenant_id);

ALTER TABLE ONLY public.control_builder
    ADD CONSTRAINT control_builder_pkey PRIMARY KEY (tenant_id, kind, record_id);

ALTER TABLE ONLY public.control_business_key_history
    ADD CONSTRAINT control_business_key_history_pkey PRIMARY KEY (tenant_id, tool_id, namespace, namespace_identity, business_key_hash, invocation_id);

ALTER TABLE ONLY public.control_business_keys
    ADD CONSTRAINT control_business_keys_pkey PRIMARY KEY (tenant_id, tool_id, namespace, namespace_identity, business_key_hash);

ALTER TABLE ONLY public.control_configuration_changes
    ADD CONSTRAINT control_configuration_changes_pkey PRIMARY KEY (tenant_id, change_id);

ALTER TABLE ONLY public.control_console_devices
    ADD CONSTRAINT control_console_devices_pkey PRIMARY KEY (tenant_id, session_id);

ALTER TABLE ONLY public.control_credential_history
    ADD CONSTRAINT control_credential_history_pkey PRIMARY KEY (tenant_id, credential_sha256);

ALTER TABLE ONLY public.control_effect_consent_approvals
    ADD CONSTRAINT control_effect_consent_approvals_pkey PRIMARY KEY (tenant_id, consent_id);

ALTER TABLE ONLY public.control_effect_consent_revocations
    ADD CONSTRAINT control_effect_consent_revocations_pkey PRIMARY KEY (tenant_id, consent_id);

ALTER TABLE ONLY public.control_effect_consent_slots
    ADD CONSTRAINT control_effect_consent_slots_pkey PRIMARY KEY (tenant_id, consent_id, slot_index);

ALTER TABLE ONLY public.control_effect_consent_uses
    ADD CONSTRAINT control_effect_consent_uses_pkey PRIMARY KEY (tenant_id, effect_id);

ALTER TABLE ONLY public.control_effect_consents
    ADD CONSTRAINT control_effect_consents_pkey PRIMARY KEY (tenant_id, consent_id);

ALTER TABLE ONLY public.control_effect_consents
    ADD CONSTRAINT control_effect_consents_tenant_id_operation_id_key UNIQUE (tenant_id, operation_id);

ALTER TABLE ONLY public.control_effect_evidence
    ADD CONSTRAINT control_effect_evidence_pkey PRIMARY KEY (tenant_id, effect_id, generation, evidence_digest);

ALTER TABLE ONLY public.control_effect_generations
    ADD CONSTRAINT control_effect_generations_pkey PRIMARY KEY (tenant_id, effect_id, generation);

ALTER TABLE ONLY public.control_effect_generations
    ADD CONSTRAINT control_effect_generations_tenant_id_auth_nonce_key UNIQUE (tenant_id, auth_nonce);

ALTER TABLE ONLY public.control_effect_intents
    ADD CONSTRAINT control_effect_intents_pkey PRIMARY KEY (tenant_id, effect_id);

ALTER TABLE ONLY public.control_effect_target_bindings
    ADD CONSTRAINT control_effect_target_bindings_pkey PRIMARY KEY (tenant_id, effect_id);

ALTER TABLE ONLY public.control_effects
    ADD CONSTRAINT control_effects_pkey PRIMARY KEY (tenant_id, effect_id);

ALTER TABLE ONLY public.control_effects
    ADD CONSTRAINT control_effects_tenant_id_operation_id_effect_id_key UNIQUE (tenant_id, operation_id, effect_id);

ALTER TABLE ONLY public.control_endpoint_configurations
    ADD CONSTRAINT control_endpoint_configurations_pkey PRIMARY KEY (tenant_id, device_id);

ALTER TABLE ONLY public.control_endpoints
    ADD CONSTRAINT control_endpoints_pkey PRIMARY KEY (tenant_id, device_id);

ALTER TABLE ONLY public.control_endpoints
    ADD CONSTRAINT control_endpoints_tenant_id_key_fingerprint_key UNIQUE (tenant_id, key_fingerprint);

ALTER TABLE ONLY public.control_enrollments
    ADD CONSTRAINT control_enrollments_pkey PRIMARY KEY (tenant_id, enrollment_id);

ALTER TABLE ONLY public.control_enrollments
    ADD CONSTRAINT control_enrollments_tenant_id_code_digest_key UNIQUE (tenant_id, code_digest);

ALTER TABLE ONLY public.control_enterprise_adoptions
    ADD CONSTRAINT control_enterprise_adoptions_pkey PRIMARY KEY (tenant_id, actor_type, actor_id);

ALTER TABLE ONLY public.control_enterprise_approvals
    ADD CONSTRAINT control_enterprise_approvals_pkey PRIMARY KEY (tenant_id, approval_id);

ALTER TABLE ONLY public.control_enterprise_invocations
    ADD CONSTRAINT control_enterprise_invocations_pkey PRIMARY KEY (tenant_id, invocation_id);

ALTER TABLE ONLY public.control_enterprise_nonces
    ADD CONSTRAINT control_enterprise_nonces_pkey PRIMARY KEY (tenant_id, nonce);

ALTER TABLE ONLY public.control_execution_budgets
    ADD CONSTRAINT control_execution_budgets_pkey PRIMARY KEY (tenant_id, budget_id, invocation_id);

ALTER TABLE ONLY public.control_execution_evidence
    ADD CONSTRAINT control_execution_evidence_pkey PRIMARY KEY (tenant_id, invocation_id, fencing_token, report_digest);

ALTER TABLE ONLY public.control_execution_leases
    ADD CONSTRAINT control_execution_leases_pkey PRIMARY KEY (tenant_id, invocation_id);

ALTER TABLE ONLY public.control_fleet
    ADD CONSTRAINT control_fleet_pkey PRIMARY KEY (tenant_id, kind, record_id);

ALTER TABLE ONLY public.control_idempotency
    ADD CONSTRAINT control_idempotency_pkey PRIMARY KEY (tenant_id, actor_id, key);

ALTER TABLE ONLY public.control_insights_admissions
    ADD CONSTRAINT control_insights_admissions_pkey PRIMARY KEY (tenant_id, bucket_start, tool_id, category);

ALTER TABLE ONLY public.control_insights_attempts
    ADD CONSTRAINT control_insights_attempts_pkey PRIMARY KEY (tenant_id, invocation_id, attempt_number);

ALTER TABLE ONLY public.control_insights_cells
    ADD CONSTRAINT control_insights_cells_pkey PRIMARY KEY (tenant_id, series, granularity, bucket_start, dim, outcome);

ALTER TABLE ONLY public.control_insights_checkpoints
    ADD CONSTRAINT control_insights_checkpoints_pkey PRIMARY KEY (tenant_id, invocation_id);

ALTER TABLE ONLY public.control_insights_facts
    ADD CONSTRAINT control_insights_facts_pkey PRIMARY KEY (tenant_id, invocation_id, fact_seq);

ALTER TABLE ONLY public.control_insights_gateway_epochs
    ADD CONSTRAINT control_insights_gateway_epochs_pkey PRIMARY KEY (tenant_id, gateway_id, epoch);

ALTER TABLE ONLY public.control_insights_invocations
    ADD CONSTRAINT control_insights_invocations_pkey PRIMARY KEY (tenant_id, invocation_id);

ALTER TABLE ONLY public.control_insights_spans
    ADD CONSTRAINT control_insights_spans_pkey PRIMARY KEY (tenant_id, invocation_id, span_id);

ALTER TABLE ONLY public.control_insights_tenant_state
    ADD CONSTRAINT control_insights_tenant_state_pkey PRIMARY KEY (tenant_id);

ALTER TABLE ONLY public.control_invocation_authority
    ADD CONSTRAINT control_invocation_authority_pkey PRIMARY KEY (tenant_id, invocation_id);

ALTER TABLE ONLY public.control_invocation_event_streams
    ADD CONSTRAINT control_invocation_event_streams_pkey PRIMARY KEY (tenant_id, invocation_id);

ALTER TABLE ONLY public.control_invocation_events
    ADD CONSTRAINT control_invocation_events_pkey PRIMARY KEY (tenant_id, invocation_id, event_cursor);

ALTER TABLE ONLY public.control_invocation_results
    ADD CONSTRAINT control_invocation_results_pkey PRIMARY KEY (tenant_id, invocation_id);

ALTER TABLE ONLY public.control_mcp_approvals
    ADD CONSTRAINT control_mcp_approvals_pkey PRIMARY KEY (tenant_id, slug, sequence);

ALTER TABLE ONLY public.control_mcp_requests
    ADD CONSTRAINT control_mcp_requests_pkey PRIMARY KEY (tenant_id, request_id);

ALTER TABLE ONLY public.control_mcp_scans
    ADD CONSTRAINT control_mcp_scans_pkey PRIMARY KEY (tenant_id, scan_id);

ALTER TABLE ONLY public.control_mcp_servers
    ADD CONSTRAINT control_mcp_servers_pkey PRIMARY KEY (tenant_id, slug);

ALTER TABLE ONLY public.control_mcp_snapshots
    ADD CONSTRAINT control_mcp_snapshots_pkey PRIMARY KEY (tenant_id, slug, revision);

ALTER TABLE ONLY public.control_operation_attempts
    ADD CONSTRAINT control_operation_attempts_pkey PRIMARY KEY (tenant_id, attempt_id);

ALTER TABLE ONLY public.control_operation_attempts
    ADD CONSTRAINT control_operation_attempts_tenant_id_operation_id_attempt_i_key UNIQUE (tenant_id, operation_id, attempt_id);

ALTER TABLE ONLY public.control_operation_cancellations
    ADD CONSTRAINT control_operation_cancellations_pkey PRIMARY KEY (tenant_id, invocation_id);

ALTER TABLE ONLY public.control_operation_outcomes
    ADD CONSTRAINT control_operation_outcomes_pkey PRIMARY KEY (tenant_id, operation_id, revision);

ALTER TABLE ONLY public.control_operation_recoveries
    ADD CONSTRAINT control_operation_recoveries_pkey PRIMARY KEY (tenant_id, operation_id, from_revision);

ALTER TABLE ONLY public.control_operation_recoveries
    ADD CONSTRAINT control_operation_recoveries_tenant_id_operation_id_to_revi_key UNIQUE (tenant_id, operation_id, to_revision);

ALTER TABLE ONLY public.control_operation_review_owners
    ADD CONSTRAINT control_operation_review_owners_pkey PRIMARY KEY (tenant_id, operation_id);

ALTER TABLE ONLY public.control_operations
    ADD CONSTRAINT control_operations_pkey PRIMARY KEY (tenant_id, operation_id);

ALTER TABLE ONLY public.control_outcome_reconciliations
    ADD CONSTRAINT control_outcome_reconciliations_pkey PRIMARY KEY (tenant_id, reconciliation_id);

ALTER TABLE ONLY public.control_package_provisioning
    ADD CONSTRAINT control_package_provisioning_pkey PRIMARY KEY (tenant_id, profile_id);

ALTER TABLE ONLY public.control_policy_bundles
    ADD CONSTRAINT control_policy_bundles_pkey PRIMARY KEY (tenant_id, sequence);

ALTER TABLE ONLY public.control_queued_authority
    ADD CONSTRAINT control_queued_authority_pkey PRIMARY KEY (tenant_id, kind, record_id);

ALTER TABLE ONLY public.control_record_ids
    ADD CONSTRAINT control_record_ids_pkey PRIMARY KEY (tenant_id, kind, record_id);

ALTER TABLE ONLY public.control_records
    ADD CONSTRAINT control_records_pkey PRIMARY KEY (tenant_id, kind, record_id);

ALTER TABLE ONLY public.control_recovery_floors
    ADD CONSTRAINT control_recovery_floors_pkey PRIMARY KEY (tenant_id);

ALTER TABLE ONLY public.control_reviewed_recovery
    ADD CONSTRAINT control_reviewed_recovery_pkey PRIMARY KEY (tenant_id, review_digest);

ALTER TABLE ONLY public.control_secrets
    ADD CONSTRAINT control_secrets_pkey PRIMARY KEY (tenant_id, name);

ALTER TABLE ONLY public.control_server_deployments
    ADD CONSTRAINT control_server_deployments_pkey PRIMARY KEY (tenant_id, package_id);

ALTER TABLE ONLY public.control_server_documents
    ADD CONSTRAINT control_server_documents_pkey PRIMARY KEY (tenant_id, digest);

ALTER TABLE ONLY public.control_server_packages
    ADD CONSTRAINT control_server_packages_pkey PRIMARY KEY (tenant_id, package_digest);

ALTER TABLE ONLY public.control_server_settings
    ADD CONSTRAINT control_server_settings_pkey PRIMARY KEY (tenant_id);

ALTER TABLE ONLY public.control_tenant_authority
    ADD CONSTRAINT control_tenant_authority_pkey PRIMARY KEY (tenant_id);

ALTER TABLE ONLY public.control_tenant_capacity
    ADD CONSTRAINT control_tenant_capacity_pkey PRIMARY KEY (tenant_id);

ALTER TABLE ONLY public.control_tenant_hosts
    ADD CONSTRAINT control_tenant_hosts_pkey PRIMARY KEY (hostname);

ALTER TABLE ONLY public.control_tenant_identity_keys
    ADD CONSTRAINT control_tenant_identity_keys_key_fingerprint_key UNIQUE (key_fingerprint);

ALTER TABLE ONLY public.control_tenant_identity_keys
    ADD CONSTRAINT control_tenant_identity_keys_pkey PRIMARY KEY (tenant_id, purpose, key_id);

ALTER TABLE ONLY public.control_tenant_initializations
    ADD CONSTRAINT control_tenant_initializations_pkey PRIMARY KEY (tenant_id);

ALTER TABLE ONLY public.control_tenant_runtime_profiles
    ADD CONSTRAINT control_tenant_runtime_profiles_pkey PRIMARY KEY (tenant_id);

ALTER TABLE ONLY public.control_tenant_vault_keys
    ADD CONSTRAINT control_tenant_vault_keys_pkey PRIMARY KEY (tenant_id, version);

ALTER TABLE ONLY public.control_tenants
    ADD CONSTRAINT control_tenants_alias_key UNIQUE (alias);

ALTER TABLE ONLY public.control_tenants
    ADD CONSTRAINT control_tenants_pkey PRIMARY KEY (tenant_id);

ALTER TABLE ONLY public.control_tool_host_dispatches
    ADD CONSTRAINT control_tool_host_dispatches_pkey PRIMARY KEY (tenant_id, invocation_id);

ALTER TABLE ONLY public.control_tool_hosts
    ADD CONSTRAINT control_tool_hosts_pkey PRIMARY KEY (tenant_id, device_id);

CREATE INDEX control_audit_tenant_cursor ON public.control_audit USING btree (tenant_id, sequence);

CREATE INDEX control_budget_window ON public.control_execution_budgets USING btree (tenant_id, budget_id, created_at);

CREATE INDEX control_business_key_invocation ON public.control_business_keys USING btree (tenant_id, invocation_id);

CREATE INDEX control_business_key_retention ON public.control_business_keys USING btree (tenant_id, retain_until) WHERE (tombstoned_at IS NULL);

CREATE INDEX control_console_devices_expiry ON public.control_console_devices USING btree (tenant_id, expires_at);

CREATE INDEX control_effect_expiry ON public.control_effect_generations USING btree (tenant_id, state, expires_at, outcome_deadline);

CREATE INDEX control_enrollment_expiry ON public.control_enrollments USING btree (tenant_id, expires_at);

CREATE INDEX control_enterprise_invocation_window ON public.control_enterprise_invocations USING btree (tenant_id, created_at);

CREATE INDEX control_idempotency_expiry ON public.control_idempotency USING btree (tenant_id, expires_at);

CREATE INDEX control_insights_attempts_execution ON public.control_insights_attempts USING btree (tenant_id, execution_id) WHERE (execution_id IS NOT NULL);

CREATE INDEX control_insights_facts_unprojected ON public.control_insights_facts USING btree (tenant_id, invocation_id, fact_seq) WHERE (projected_at IS NULL);

CREATE INDEX control_insights_invocations_accepted ON public.control_insights_invocations USING btree (tenant_id, accepted_at);

CREATE INDEX control_insights_invocations_parent ON public.control_insights_invocations USING btree (tenant_id, parent_invocation_id) WHERE (parent_invocation_id IS NOT NULL);

CREATE INDEX control_insights_invocations_run ON public.control_insights_invocations USING btree (tenant_id, agent_run_id) WHERE (agent_run_id IS NOT NULL);

CREATE INDEX control_insights_invocations_session ON public.control_insights_invocations USING btree (tenant_id, session_id) WHERE (session_id IS NOT NULL);

CREATE INDEX control_insights_invocations_tool_call ON public.control_insights_invocations USING btree (tenant_id, tool_call_id) WHERE (tool_call_id IS NOT NULL);

CREATE INDEX control_insights_spans_stored ON public.control_insights_spans USING btree (tenant_id, stored_at);

CREATE INDEX control_invocation_active_package ON public.control_enterprise_invocations USING btree (tenant_id, package_digest) WHERE ((state)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'QUEUED'::character varying, 'RESERVED'::character varying, 'DISPATCHED'::character varying, 'EXECUTING'::character varying])::text[]));

CREATE INDEX control_invocation_active_tool ON public.control_enterprise_invocations USING btree (tenant_id, tool_id) WHERE ((state)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'QUEUED'::character varying, 'RESERVED'::character varying, 'DISPATCHED'::character varying, 'EXECUTING'::character varying])::text[]));

CREATE UNIQUE INDEX control_invocation_event_identity ON public.control_invocation_events USING btree (tenant_id, invocation_id, fencing_token, sequence) WHERE (sequence IS NOT NULL);

CREATE INDEX control_invocation_event_stream_retention ON public.control_invocation_event_streams USING btree (tenant_id, retain_until) WHERE (purged_at IS NULL);

CREATE INDEX control_invocation_input_deadline ON public.control_enterprise_invocations USING btree (tenant_id, task_input_deadline_at) WHERE ((task_state)::text = ANY ((ARRAY['AWAITING_APPROVAL'::character varying, 'INPUT_REQUIRED'::character varying])::text[]));

CREATE INDEX control_invocation_lease ON public.control_enterprise_invocations USING btree (tenant_id, lease_expires_at) WHERE ((state)::text = 'EXECUTING'::text);

CREATE INDEX control_invocation_result_retention ON public.control_invocation_results USING btree (tenant_id, retain_until) WHERE (purged_at IS NULL);

CREATE UNIQUE INDEX control_invocation_task ON public.control_enterprise_invocations USING btree (tenant_id, task_id) WHERE (task_id IS NOT NULL);

CREATE INDEX control_invocation_task_retention ON public.control_enterprise_invocations USING btree (tenant_id, task_retain_until) WHERE (task_id IS NOT NULL);

CREATE INDEX control_mcp_pending ON public.control_mcp_requests USING btree (tenant_id, device_id, state, received_at);

CREATE INDEX control_mcp_scans_queue ON public.control_mcp_scans USING btree (tenant_id, host_id, state, created_at);

CREATE INDEX control_mcp_scans_server ON public.control_mcp_scans USING btree (tenant_id, slug, state);

CREATE INDEX control_nonce_operation_purpose ON public.control_enterprise_nonces USING btree (tenant_id, invocation_id, purpose);

CREATE UNIQUE INDEX control_one_live_effect_generation ON public.control_effect_generations USING btree (tenant_id, effect_id) WHERE (state = ANY (ARRAY['AUTHORIZED'::text, 'SENDING'::text, 'CONSUMED'::text]));

CREATE INDEX control_tenant_hosts_owner ON public.control_tenant_hosts USING btree (tenant_id);

CREATE UNIQUE INDEX control_tenant_identity_keys_active ON public.control_tenant_identity_keys USING btree (tenant_id, purpose) WHERE ((state)::text = 'ACTIVE'::text);

CREATE UNIQUE INDEX control_tenant_vault_keys_current ON public.control_tenant_vault_keys USING btree (tenant_id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE INDEX control_tool_host_dispatches_queue ON public.control_tool_host_dispatches USING btree (tenant_id, host_id, state, created_at);

CREATE TRIGGER attempt_binding_guard BEFORE INSERT ON public.control_operation_attempts FOR EACH ROW EXECUTE FUNCTION public.control_attempt_binding_guard();

CREATE TRIGGER attempt_effect_guard BEFORE INSERT ON public.control_attempt_effects FOR EACH ROW EXECUTE FUNCTION public.control_attempt_effect_guard();

CREATE TRIGGER attempt_effects_capacity AFTER INSERT ON public.control_attempt_effects FOR EACH ROW EXECUTE FUNCTION public.control_attempt_effects_capacity();

CREATE TRIGGER audit_chain_guard BEFORE INSERT ON public.control_audit FOR EACH ROW EXECUTE FUNCTION public.control_audit_chain_guard();

CREATE TRIGGER audit_immutable BEFORE DELETE OR UPDATE ON public.control_audit FOR EACH ROW EXECUTE FUNCTION public.control_audit_immutable();

CREATE TRIGGER audit_anchors_immutable BEFORE DELETE OR UPDATE ON public.control_audit_anchors FOR EACH ROW EXECUTE FUNCTION public.control_audit_immutable();

CREATE TRIGGER attempt_effects_immutable BEFORE DELETE OR UPDATE ON public.control_attempt_effects FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();

CREATE TRIGGER attempt_immutable BEFORE DELETE OR UPDATE ON public.control_operation_attempts FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();

CREATE TRIGGER attempt_manifest_seal_guard BEFORE INSERT ON public.control_attempt_manifest_seals FOR EACH ROW EXECUTE FUNCTION public.control_attempt_manifest_seal_guard();

CREATE TRIGGER attempt_manifest_seals_capacity AFTER INSERT ON public.control_attempt_manifest_seals FOR EACH ROW EXECUTE FUNCTION public.control_attempt_manifest_seals_capacity();

CREATE TRIGGER attempt_manifest_seals_immutable BEFORE DELETE OR UPDATE ON public.control_attempt_manifest_seals FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();

CREATE TRIGGER attempt_reservation_guard BEFORE INSERT ON public.control_enterprise_nonces FOR EACH ROW EXECUTE FUNCTION public.control_attempt_reservation_guard();

CREATE TRIGGER attempt_review_capacity AFTER INSERT ON public.control_attempt_reviews FOR EACH ROW EXECUTE FUNCTION public.control_attempt_review_capacity();

CREATE TRIGGER attempt_review_guard BEFORE INSERT ON public.control_attempt_reviews FOR EACH ROW EXECUTE FUNCTION public.control_attempt_review_guard();

CREATE TRIGGER attempt_review_immutable BEFORE DELETE OR UPDATE ON public.control_attempt_reviews FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();

CREATE TRIGGER attempt_settlement_capacity AFTER INSERT ON public.control_attempt_settlements FOR EACH ROW EXECUTE FUNCTION public.control_attempt_settlement_capacity();

CREATE TRIGGER attempt_settlement_guard BEFORE INSERT ON public.control_attempt_settlements FOR EACH ROW EXECUTE FUNCTION public.control_attempt_settlement_guard();

CREATE TRIGGER attempt_settlement_immutable BEFORE DELETE OR UPDATE ON public.control_attempt_settlements FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();





























CREATE TRIGGER consent_approval_guard BEFORE INSERT ON public.control_effect_consent_approvals FOR EACH ROW EXECUTE FUNCTION public.consent_approval_guard();

CREATE TRIGGER consent_approvals_capacity AFTER INSERT ON public.control_effect_consent_approvals FOR EACH ROW EXECUTE FUNCTION public.consent_approvals_capacity();

CREATE TRIGGER consent_approvals_immutable BEFORE DELETE OR UPDATE ON public.control_effect_consent_approvals FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();

CREATE TRIGGER consent_revocations_capacity AFTER INSERT ON public.control_effect_consent_revocations FOR EACH ROW EXECUTE FUNCTION public.consent_revocations_capacity();

CREATE TRIGGER consent_revocations_immutable BEFORE DELETE OR UPDATE ON public.control_effect_consent_revocations FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();

CREATE TRIGGER consent_slots_capacity AFTER INSERT ON public.control_effect_consent_slots FOR EACH ROW EXECUTE FUNCTION public.consent_slots_capacity();

CREATE TRIGGER consent_slots_immutable BEFORE DELETE OR UPDATE ON public.control_effect_consent_slots FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();

CREATE TRIGGER consent_use_guard BEFORE INSERT ON public.control_effect_consent_uses FOR EACH ROW EXECUTE FUNCTION public.consent_use_guard();

CREATE TRIGGER consent_uses_capacity AFTER INSERT ON public.control_effect_consent_uses FOR EACH ROW EXECUTE FUNCTION public.consent_uses_capacity();

CREATE TRIGGER consent_uses_immutable BEFORE DELETE OR UPDATE ON public.control_effect_consent_uses FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();

CREATE TRIGGER consents_capacity AFTER INSERT ON public.control_effect_consents FOR EACH ROW EXECUTE FUNCTION public.consents_capacity();

CREATE TRIGGER consents_immutable BEFORE DELETE OR UPDATE ON public.control_effect_consents FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();

CREATE TRIGGER control_builder_guard BEFORE DELETE OR UPDATE ON public.control_builder FOR EACH ROW EXECUTE FUNCTION public.control_builder_guard();

CREATE TRIGGER control_business_key_guard BEFORE DELETE OR UPDATE ON public.control_business_keys FOR EACH ROW EXECUTE FUNCTION public.control_business_key_guard();

CREATE TRIGGER control_business_key_history_immutable BEFORE DELETE OR UPDATE ON public.control_business_key_history FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();

CREATE TRIGGER control_business_key_history_insert BEFORE INSERT ON public.control_business_key_history FOR EACH ROW EXECUTE FUNCTION public.control_business_key_history_insert_guard();



CREATE TRIGGER control_credential_history_immutable BEFORE DELETE OR UPDATE ON public.control_credential_history FOR EACH ROW EXECUTE FUNCTION public.control_credential_immutable();

CREATE TRIGGER control_epoch_guard BEFORE UPDATE ON public.control_tenant_authority FOR EACH ROW EXECUTE FUNCTION public.control_epoch_guard();

CREATE TRIGGER control_fleet_guard BEFORE DELETE OR UPDATE ON public.control_fleet FOR EACH ROW EXECUTE FUNCTION public.control_fleet_guard();

CREATE TRIGGER control_invocation_result_guard BEFORE DELETE OR UPDATE ON public.control_invocation_results FOR EACH ROW EXECUTE FUNCTION public.control_invocation_result_guard();

CREATE TRIGGER control_operation_cancellations_immutable BEFORE DELETE OR UPDATE ON public.control_operation_cancellations FOR EACH ROW EXECUTE FUNCTION public.control_credential_immutable();

CREATE TRIGGER control_records_cache_generation AFTER INSERT OR DELETE OR UPDATE ON public.control_records FOR EACH ROW EXECUTE FUNCTION public.control_invalidate_directory_cache();

CREATE TRIGGER control_reviewed_recovery_immutable BEFORE DELETE OR UPDATE ON public.control_reviewed_recovery FOR EACH ROW EXECUTE FUNCTION public.control_credential_immutable();

CREATE TRIGGER control_secret_version_guard BEFORE UPDATE ON public.control_secrets FOR EACH ROW EXECUTE FUNCTION public.control_secret_version_guard();

CREATE TRIGGER control_settings_cache_generation AFTER INSERT OR DELETE OR UPDATE ON public.control_server_settings FOR EACH ROW EXECUTE FUNCTION public.control_invalidate_directory_cache();

CREATE TRIGGER control_tenant_identity_guard BEFORE DELETE OR UPDATE ON public.control_tenants FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER control_tenant_identity_key_guard BEFORE DELETE OR UPDATE ON public.control_tenant_identity_keys FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_key_guard();

CREATE TRIGGER control_tenant_initializations_immutable BEFORE DELETE OR UPDATE ON public.control_tenant_initializations FOR EACH ROW EXECUTE FUNCTION public.control_credential_immutable();

CREATE TRIGGER control_tenant_lifecycle_guard BEFORE UPDATE ON public.control_tenants FOR EACH ROW EXECUTE FUNCTION public.control_tenant_lifecycle_guard();

CREATE TRIGGER control_vault_key_guard BEFORE DELETE OR UPDATE ON public.control_tenant_vault_keys FOR EACH ROW EXECUTE FUNCTION public.control_vault_key_guard();

CREATE TRIGGER effect_evidence_immutable BEFORE DELETE OR UPDATE ON public.control_effect_evidence FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();

CREATE TRIGGER effect_family_insert_guard BEFORE INSERT ON public.control_effects FOR EACH ROW EXECUTE FUNCTION public.control_effect_family_insert_guard();

CREATE TRIGGER effect_generation_guard BEFORE DELETE OR UPDATE ON public.control_effect_generations FOR EACH ROW EXECUTE FUNCTION public.control_effect_generation_guard();

CREATE TRIGGER effect_identity_guard BEFORE DELETE OR UPDATE ON public.control_effects FOR EACH ROW EXECUTE FUNCTION public.control_effect_identity_guard();

CREATE TRIGGER effect_insert_guard BEFORE INSERT ON public.control_effect_generations FOR EACH ROW EXECUTE FUNCTION public.control_effect_insert_guard();

CREATE TRIGGER effect_intent_capacity AFTER INSERT ON public.control_effect_intents FOR EACH ROW EXECUTE FUNCTION public.control_effect_intent_capacity();

CREATE TRIGGER effect_intent_guard BEFORE INSERT ON public.control_effect_intents FOR EACH ROW EXECUTE FUNCTION public.control_effect_intent_guard();

CREATE TRIGGER effect_intent_immutable BEFORE DELETE OR UPDATE ON public.control_effect_intents FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();

CREATE TRIGGER effect_manifest_guard BEFORE INSERT ON public.control_effect_generations FOR EACH ROW EXECUTE FUNCTION public.control_effect_manifest_guard();

CREATE TRIGGER effect_manifest_member AFTER INSERT ON public.control_effect_generations FOR EACH ROW EXECUTE FUNCTION public.control_effect_manifest_member();

CREATE TRIGGER effect_manifest_transition_guard BEFORE UPDATE ON public.control_effect_generations FOR EACH ROW EXECUTE FUNCTION public.control_effect_manifest_transition_guard();

CREATE TRIGGER effect_target_binding_capacity AFTER INSERT ON public.control_effect_target_bindings FOR EACH ROW EXECUTE FUNCTION public.effect_target_binding_capacity();

CREATE TRIGGER effect_target_binding_guard BEFORE INSERT ON public.control_effect_target_bindings FOR EACH ROW EXECUTE FUNCTION public.control_effect_target_binding_guard();

CREATE TRIGGER effect_target_binding_immutable BEFORE DELETE OR UPDATE ON public.control_effect_target_bindings FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();

CREATE TRIGGER execution_evidence_immutable BEFORE DELETE OR UPDATE ON public.control_execution_evidence FOR EACH ROW EXECUTE FUNCTION public.control_execution_evidence_guard();

CREATE TRIGGER execution_identity BEFORE UPDATE ON public.control_execution_leases FOR EACH ROW EXECUTE FUNCTION public.control_execution_identity_guard();

CREATE TRIGGER execution_reconciliation_guard BEFORE INSERT OR UPDATE ON public.control_enterprise_nonces FOR EACH ROW EXECUTE FUNCTION public.control_execution_reconciliation_guard();

CREATE TRIGGER execution_report_qualification_guard BEFORE INSERT ON public.control_execution_evidence FOR EACH ROW EXECUTE FUNCTION public.control_execution_report_qualification_guard();

CREATE TRIGGER invocation_recovery_guard BEFORE UPDATE ON public.control_enterprise_invocations FOR EACH ROW EXECUTE FUNCTION public.control_invocation_recovery_guard();

CREATE TRIGGER nonce_identity_guard BEFORE UPDATE ON public.control_enterprise_nonces FOR EACH ROW EXECUTE FUNCTION public.control_nonce_identity_guard();

CREATE TRIGGER operation_guard BEFORE DELETE OR UPDATE ON public.control_operations FOR EACH ROW EXECUTE FUNCTION public.control_operation_guard();

CREATE TRIGGER operation_outcome_capacity AFTER INSERT ON public.control_operation_outcomes FOR EACH ROW EXECUTE FUNCTION public.control_operation_outcome_capacity();

CREATE TRIGGER operation_outcome_guard BEFORE INSERT ON public.control_operation_outcomes FOR EACH ROW EXECUTE FUNCTION public.control_operation_outcome_guard();

CREATE TRIGGER operation_outcome_immutable BEFORE DELETE OR UPDATE ON public.control_operation_outcomes FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();

CREATE TRIGGER operation_recovery_capacity AFTER INSERT ON public.control_operation_recoveries FOR EACH ROW EXECUTE FUNCTION public.control_operation_recovery_capacity();

CREATE TRIGGER operation_recovery_guard BEFORE INSERT OR DELETE OR UPDATE ON public.control_operation_recoveries FOR EACH ROW EXECUTE FUNCTION public.control_operation_recovery_guard();

CREATE TRIGGER original_deadline_guard BEFORE UPDATE ON public.control_enterprise_invocations FOR EACH ROW EXECUTE FUNCTION public.control_original_deadline_guard();

CREATE TRIGGER reconciliation_history_guard BEFORE INSERT OR DELETE OR UPDATE ON public.control_outcome_reconciliations FOR EACH ROW EXECUTE FUNCTION public.control_reconciliation_history_guard();

CREATE TRIGGER recovery_scope_capacity AFTER INSERT ON public.control_attempt_recovery_scopes FOR EACH ROW EXECUTE FUNCTION public.control_recovery_scope_capacity();

CREATE TRIGGER recovery_scope_guard BEFORE INSERT OR DELETE OR UPDATE ON public.control_attempt_recovery_scopes FOR EACH ROW EXECUTE FUNCTION public.control_recovery_scope_guard();

CREATE TRIGGER review_owners_capacity AFTER INSERT ON public.control_operation_review_owners FOR EACH ROW EXECUTE FUNCTION public.control_review_owners_capacity();

CREATE TRIGGER review_owners_guard BEFORE INSERT ON public.control_operation_review_owners FOR EACH ROW EXECUTE FUNCTION public.control_review_owners_guard();

CREATE TRIGGER review_owners_immutable BEFORE DELETE OR UPDATE ON public.control_operation_review_owners FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();

CREATE TRIGGER snapshot_immutable BEFORE DELETE OR UPDATE ON public.control_attempt_snapshots FOR EACH ROW EXECUTE FUNCTION public.control_attempt_immutable();

CREATE TRIGGER snapshot_transition_guard BEFORE INSERT ON public.control_attempt_snapshots FOR EACH ROW EXECUTE FUNCTION public.control_snapshot_transition_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_approval_clocks FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_audit FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_authorization_outbox FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_bootstrap_admins FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_builder FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_business_keys FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_configuration_changes FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_console_devices FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_credential_history FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_endpoint_configurations FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_endpoints FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_enrollments FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_enterprise_adoptions FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_enterprise_approvals FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_enterprise_invocations FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_enterprise_nonces FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_execution_budgets FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_execution_leases FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_fleet FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_idempotency FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_insights_admissions FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_insights_attempts FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_insights_cells FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_insights_checkpoints FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_insights_facts FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_insights_gateway_epochs FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_insights_invocations FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_insights_spans FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_insights_tenant_state FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_invocation_authority FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_invocation_event_streams FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_invocation_events FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_invocation_results FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_mcp_approvals FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_mcp_requests FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_mcp_scans FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_mcp_servers FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_mcp_snapshots FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_operation_cancellations FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_outcome_reconciliations FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_package_provisioning FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_policy_bundles FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_queued_authority FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_record_ids FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_records FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_recovery_floors FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_reviewed_recovery FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_secrets FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_server_deployments FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_server_documents FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_server_packages FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_server_settings FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_tenant_authority FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_tenant_capacity FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_tenant_identity_keys FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_tenant_initializations FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_tenant_runtime_profiles FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_tenant_vault_keys FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_tool_host_dispatches FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_tool_hosts FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();

ALTER TABLE ONLY public.control_approval_clocks
    ADD CONSTRAINT control_approval_clocks_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_attempt_effects
    ADD CONSTRAINT control_attempt_effects_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_attempt_effects
    ADD CONSTRAINT control_attempt_effects_tenant_id_operation_id_attempt_id_fkey FOREIGN KEY (tenant_id, operation_id, attempt_id) REFERENCES public.control_operation_attempts(tenant_id, operation_id, attempt_id);

ALTER TABLE ONLY public.control_attempt_effects
    ADD CONSTRAINT control_attempt_effects_tenant_id_operation_id_effect_id_fkey FOREIGN KEY (tenant_id, operation_id, effect_id) REFERENCES public.control_effects(tenant_id, operation_id, effect_id);

ALTER TABLE ONLY public.control_attempt_manifest_seals
    ADD CONSTRAINT control_attempt_manifest_seal_tenant_id_operation_id_attem_fkey FOREIGN KEY (tenant_id, operation_id, attempt_id) REFERENCES public.control_operation_attempts(tenant_id, operation_id, attempt_id);

ALTER TABLE ONLY public.control_attempt_manifest_seals
    ADD CONSTRAINT control_attempt_manifest_seals_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_attempt_recovery_scopes
    ADD CONSTRAINT control_attempt_recovery_scop_tenant_id_operation_id_attem_fkey FOREIGN KEY (tenant_id, operation_id, attempt_id) REFERENCES public.control_operation_attempts(tenant_id, operation_id, attempt_id);

ALTER TABLE ONLY public.control_attempt_recovery_scopes
    ADD CONSTRAINT control_attempt_recovery_scopes_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_attempt_reviews
    ADD CONSTRAINT control_attempt_reviews_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_attempt_reviews
    ADD CONSTRAINT control_attempt_reviews_tenant_id_operation_id_attempt_id_fkey FOREIGN KEY (tenant_id, operation_id, attempt_id) REFERENCES public.control_attempt_manifest_seals(tenant_id, operation_id, attempt_id);

ALTER TABLE ONLY public.control_attempt_reviews
    ADD CONSTRAINT control_attempt_reviews_tenant_id_operation_id_expected_ou_fkey FOREIGN KEY (tenant_id, operation_id, expected_outcome_revision) REFERENCES public.control_operation_outcomes(tenant_id, operation_id, revision);

ALTER TABLE ONLY public.control_attempt_reviews
    ADD CONSTRAINT control_attempt_reviews_tenant_id_reconciliation_id_fkey FOREIGN KEY (tenant_id, reconciliation_id) REFERENCES public.control_outcome_reconciliations(tenant_id, reconciliation_id);

ALTER TABLE ONLY public.control_attempt_settlements
    ADD CONSTRAINT control_attempt_settlements_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_attempt_settlements
    ADD CONSTRAINT control_attempt_settlements_tenant_id_operation_id_attempt_fkey FOREIGN KEY (tenant_id, operation_id, attempt_id) REFERENCES public.control_attempt_manifest_seals(tenant_id, operation_id, attempt_id);

ALTER TABLE ONLY public.control_attempt_snapshots
    ADD CONSTRAINT control_attempt_snapshots_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_attempt_snapshots
    ADD CONSTRAINT control_attempt_snapshots_tenant_id_operation_id_attempt_i_fkey FOREIGN KEY (tenant_id, operation_id, attempt_id) REFERENCES public.control_operation_attempts(tenant_id, operation_id, attempt_id);

ALTER TABLE ONLY public.control_audit
    ADD CONSTRAINT control_audit_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_audit_anchors
    ADD CONSTRAINT control_audit_anchors_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_authorization_outbox
    ADD CONSTRAINT control_authorization_outbox_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_bootstrap_admins
    ADD CONSTRAINT control_bootstrap_admins_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_builder
    ADD CONSTRAINT control_builder_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_business_key_history
    ADD CONSTRAINT control_business_key_history_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_business_key_history
    ADD CONSTRAINT control_business_key_history_tenant_id_invocation_id_fkey FOREIGN KEY (tenant_id, invocation_id) REFERENCES public.control_operations(tenant_id, operation_id);

ALTER TABLE ONLY public.control_business_keys
    ADD CONSTRAINT control_business_keys_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_configuration_changes
    ADD CONSTRAINT control_configuration_changes_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_console_devices
    ADD CONSTRAINT control_console_devices_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_credential_history
    ADD CONSTRAINT control_credential_history_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_effect_consent_approvals
    ADD CONSTRAINT control_effect_consent_approvals_tenant_id_consent_id_fkey FOREIGN KEY (tenant_id, consent_id) REFERENCES public.control_effect_consents(tenant_id, consent_id);

ALTER TABLE ONLY public.control_effect_consent_approvals
    ADD CONSTRAINT control_effect_consent_approvals_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_effect_consent_revocations
    ADD CONSTRAINT control_effect_consent_revocations_tenant_id_consent_id_fkey FOREIGN KEY (tenant_id, consent_id) REFERENCES public.control_effect_consents(tenant_id, consent_id);

ALTER TABLE ONLY public.control_effect_consent_revocations
    ADD CONSTRAINT control_effect_consent_revocations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_effect_consent_slots
    ADD CONSTRAINT control_effect_consent_slots_tenant_id_consent_id_fkey FOREIGN KEY (tenant_id, consent_id) REFERENCES public.control_effect_consents(tenant_id, consent_id);

ALTER TABLE ONLY public.control_effect_consent_slots
    ADD CONSTRAINT control_effect_consent_slots_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_effect_consent_uses
    ADD CONSTRAINT control_effect_consent_uses_tenant_id_consent_id_slot_inde_fkey FOREIGN KEY (tenant_id, consent_id, slot_index) REFERENCES public.control_effect_consent_slots(tenant_id, consent_id, slot_index);

ALTER TABLE ONLY public.control_effect_consent_uses
    ADD CONSTRAINT control_effect_consent_uses_tenant_id_effect_id_fkey FOREIGN KEY (tenant_id, effect_id) REFERENCES public.control_effects(tenant_id, effect_id);

ALTER TABLE ONLY public.control_effect_consent_uses
    ADD CONSTRAINT control_effect_consent_uses_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_effect_consents
    ADD CONSTRAINT control_effect_consents_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_effect_consents
    ADD CONSTRAINT control_effect_consents_tenant_id_operation_id_fkey FOREIGN KEY (tenant_id, operation_id) REFERENCES public.control_enterprise_invocations(tenant_id, invocation_id);

ALTER TABLE ONLY public.control_effect_evidence
    ADD CONSTRAINT control_effect_evidence_tenant_id_effect_id_generation_fkey FOREIGN KEY (tenant_id, effect_id, generation) REFERENCES public.control_effect_generations(tenant_id, effect_id, generation);

ALTER TABLE ONLY public.control_effect_evidence
    ADD CONSTRAINT control_effect_evidence_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_effect_generations
    ADD CONSTRAINT control_effect_generations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_effect_generations
    ADD CONSTRAINT control_effect_generations_tenant_id_operation_id_attempt__fkey FOREIGN KEY (tenant_id, operation_id, attempt_id) REFERENCES public.control_operation_attempts(tenant_id, operation_id, attempt_id);

ALTER TABLE ONLY public.control_effect_generations
    ADD CONSTRAINT control_effect_generations_tenant_id_operation_id_effect_i_fkey FOREIGN KEY (tenant_id, operation_id, effect_id) REFERENCES public.control_effects(tenant_id, operation_id, effect_id);

ALTER TABLE ONLY public.control_effect_intents
    ADD CONSTRAINT control_effect_intents_tenant_id_effect_id_fkey FOREIGN KEY (tenant_id, effect_id) REFERENCES public.control_effects(tenant_id, effect_id);

ALTER TABLE ONLY public.control_effect_intents
    ADD CONSTRAINT control_effect_intents_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_effect_target_bindings
    ADD CONSTRAINT control_effect_target_bindings_tenant_id_effect_id_fkey FOREIGN KEY (tenant_id, effect_id) REFERENCES public.control_effects(tenant_id, effect_id);

ALTER TABLE ONLY public.control_effect_target_bindings
    ADD CONSTRAINT control_effect_target_bindings_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_effect_target_bindings
    ADD CONSTRAINT control_effect_target_bindings_tenant_id_operation_id_fkey FOREIGN KEY (tenant_id, operation_id) REFERENCES public.control_operations(tenant_id, operation_id);

ALTER TABLE ONLY public.control_effects
    ADD CONSTRAINT control_effects_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_effects
    ADD CONSTRAINT control_effects_tenant_id_operation_id_fkey FOREIGN KEY (tenant_id, operation_id) REFERENCES public.control_operations(tenant_id, operation_id);

ALTER TABLE ONLY public.control_endpoint_configurations
    ADD CONSTRAINT control_endpoint_configurations_tenant_id_device_id_fkey FOREIGN KEY (tenant_id, device_id) REFERENCES public.control_endpoints(tenant_id, device_id);

ALTER TABLE ONLY public.control_endpoint_configurations
    ADD CONSTRAINT control_endpoint_configurations_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_endpoints
    ADD CONSTRAINT control_endpoints_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_enrollments
    ADD CONSTRAINT control_enrollments_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_enterprise_adoptions
    ADD CONSTRAINT control_enterprise_adoptions_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_enterprise_approvals
    ADD CONSTRAINT control_enterprise_approvals_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_enterprise_invocations
    ADD CONSTRAINT control_enterprise_invocations_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_enterprise_nonces
    ADD CONSTRAINT control_enterprise_nonces_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_execution_budgets
    ADD CONSTRAINT control_execution_budgets_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_execution_evidence
    ADD CONSTRAINT control_execution_evidence_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_execution_evidence
    ADD CONSTRAINT control_execution_evidence_tenant_id_invocation_id_fkey FOREIGN KEY (tenant_id, invocation_id) REFERENCES public.control_enterprise_invocations(tenant_id, invocation_id);

ALTER TABLE ONLY public.control_execution_leases
    ADD CONSTRAINT control_execution_leases_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_execution_leases
    ADD CONSTRAINT control_execution_leases_tenant_id_invocation_id_fkey FOREIGN KEY (tenant_id, invocation_id) REFERENCES public.control_enterprise_invocations(tenant_id, invocation_id);

ALTER TABLE ONLY public.control_fleet
    ADD CONSTRAINT control_fleet_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_idempotency
    ADD CONSTRAINT control_idempotency_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_insights_admissions
    ADD CONSTRAINT control_insights_admissions_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_insights_attempts
    ADD CONSTRAINT control_insights_attempts_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_insights_cells
    ADD CONSTRAINT control_insights_cells_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_insights_checkpoints
    ADD CONSTRAINT control_insights_checkpoints_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_insights_facts
    ADD CONSTRAINT control_insights_facts_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_insights_gateway_epochs
    ADD CONSTRAINT control_insights_gateway_epochs_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_insights_invocations
    ADD CONSTRAINT control_insights_invocations_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_insights_spans
    ADD CONSTRAINT control_insights_spans_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_insights_tenant_state
    ADD CONSTRAINT control_insights_tenant_state_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_invocation_authority
    ADD CONSTRAINT control_invocation_authority_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_invocation_event_streams
    ADD CONSTRAINT control_invocation_event_streams_tenant_id_invocation_id_fkey FOREIGN KEY (tenant_id, invocation_id) REFERENCES public.control_enterprise_invocations(tenant_id, invocation_id);

ALTER TABLE ONLY public.control_invocation_event_streams
    ADD CONSTRAINT control_invocation_event_streams_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_invocation_events
    ADD CONSTRAINT control_invocation_events_tenant_id_invocation_id_fkey FOREIGN KEY (tenant_id, invocation_id) REFERENCES public.control_invocation_event_streams(tenant_id, invocation_id);

ALTER TABLE ONLY public.control_invocation_events
    ADD CONSTRAINT control_invocation_events_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_invocation_results
    ADD CONSTRAINT control_invocation_results_tenant_id_invocation_id_fkey FOREIGN KEY (tenant_id, invocation_id) REFERENCES public.control_enterprise_invocations(tenant_id, invocation_id);

ALTER TABLE ONLY public.control_invocation_results
    ADD CONSTRAINT control_invocation_results_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_mcp_approvals
    ADD CONSTRAINT control_mcp_approvals_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_mcp_requests
    ADD CONSTRAINT control_mcp_requests_tenant_id_device_id_fkey FOREIGN KEY (tenant_id, device_id) REFERENCES public.control_endpoints(tenant_id, device_id);

ALTER TABLE ONLY public.control_mcp_requests
    ADD CONSTRAINT control_mcp_requests_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_mcp_scans
    ADD CONSTRAINT control_mcp_scans_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_mcp_servers
    ADD CONSTRAINT control_mcp_servers_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_mcp_snapshots
    ADD CONSTRAINT control_mcp_snapshots_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_operation_attempts
    ADD CONSTRAINT control_operation_attempts_tenant_id_attempt_id_fkey FOREIGN KEY (tenant_id, attempt_id) REFERENCES public.control_enterprise_nonces(tenant_id, nonce);

ALTER TABLE ONLY public.control_operation_attempts
    ADD CONSTRAINT control_operation_attempts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_operation_attempts
    ADD CONSTRAINT control_operation_attempts_tenant_id_operation_id_fkey FOREIGN KEY (tenant_id, operation_id) REFERENCES public.control_operations(tenant_id, operation_id);

ALTER TABLE ONLY public.control_operation_cancellations
    ADD CONSTRAINT control_operation_cancellations_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_operation_outcomes
    ADD CONSTRAINT control_operation_outcomes_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_operation_outcomes
    ADD CONSTRAINT control_operation_outcomes_tenant_id_operation_id_fkey FOREIGN KEY (tenant_id, operation_id) REFERENCES public.control_operations(tenant_id, operation_id);

ALTER TABLE ONLY public.control_operation_recoveries
    ADD CONSTRAINT control_operation_recoveries_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_operation_recoveries
    ADD CONSTRAINT control_operation_recoveries_tenant_id_operation_id_fkey FOREIGN KEY (tenant_id, operation_id) REFERENCES public.control_operations(tenant_id, operation_id);

ALTER TABLE ONLY public.control_operation_recoveries
    ADD CONSTRAINT control_operation_recoveries_tenant_id_operation_id_previo_fkey FOREIGN KEY (tenant_id, operation_id, previous_nonce) REFERENCES public.control_operation_attempts(tenant_id, operation_id, attempt_id);

ALTER TABLE ONLY public.control_operation_review_owners
    ADD CONSTRAINT control_operation_review_owners_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_operation_review_owners
    ADD CONSTRAINT control_operation_review_owners_tenant_id_operation_id_fkey FOREIGN KEY (tenant_id, operation_id) REFERENCES public.control_operations(tenant_id, operation_id);

ALTER TABLE ONLY public.control_operations
    ADD CONSTRAINT control_operations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_operations
    ADD CONSTRAINT control_operations_tenant_id_operation_id_fkey FOREIGN KEY (tenant_id, operation_id) REFERENCES public.control_enterprise_invocations(tenant_id, invocation_id);

ALTER TABLE ONLY public.control_outcome_reconciliations
    ADD CONSTRAINT control_outcome_reconciliations_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_package_provisioning
    ADD CONSTRAINT control_package_provisioning_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_policy_bundles
    ADD CONSTRAINT control_policy_bundles_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_queued_authority
    ADD CONSTRAINT control_queued_authority_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_record_ids
    ADD CONSTRAINT control_record_ids_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_records
    ADD CONSTRAINT control_records_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_recovery_floors
    ADD CONSTRAINT control_recovery_floors_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_reviewed_recovery
    ADD CONSTRAINT control_reviewed_recovery_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_secrets
    ADD CONSTRAINT control_secrets_tenant_id_key_version_fkey FOREIGN KEY (tenant_id, key_version) REFERENCES public.control_tenant_vault_keys(tenant_id, version);

ALTER TABLE ONLY public.control_secrets
    ADD CONSTRAINT control_secrets_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_server_deployments
    ADD CONSTRAINT control_server_deployments_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_server_documents
    ADD CONSTRAINT control_server_documents_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_server_packages
    ADD CONSTRAINT control_server_packages_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_server_settings
    ADD CONSTRAINT control_server_settings_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_tenant_authority
    ADD CONSTRAINT control_tenant_authority_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_tenant_capacity
    ADD CONSTRAINT control_tenant_capacity_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_tenant_hosts
    ADD CONSTRAINT control_tenant_hosts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_tenant_identity_keys
    ADD CONSTRAINT control_tenant_identity_keys_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_tenant_initializations
    ADD CONSTRAINT control_tenant_initializations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_tenant_runtime_profiles
    ADD CONSTRAINT control_tenant_runtime_profiles_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_tenant_vault_keys
    ADD CONSTRAINT control_tenant_vault_keys_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_tool_host_dispatches
    ADD CONSTRAINT control_tool_host_dispatches_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE ONLY public.control_tool_hosts
    ADD CONSTRAINT control_tool_hosts_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id);

ALTER TABLE public.control_approval_clocks ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_attempt_effects ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_attempt_manifest_seals ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_attempt_recovery_scopes ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_attempt_reviews ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_attempt_settlements ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_attempt_snapshots ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_audit ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_audit_anchors ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_authorization_outbox ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_bootstrap_admins ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_builder ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_business_key_history ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_business_keys ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_configuration_changes ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_console_devices ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_credential_history ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_effect_consent_approvals ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_effect_consent_revocations ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_effect_consent_slots ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_effect_consent_uses ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_effect_consents ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_effect_evidence ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_effect_generations ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_effect_intents ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_effect_target_bindings ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_effects ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_endpoint_configurations ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_endpoints ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_enrollments ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_enterprise_adoptions ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_enterprise_approvals ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_enterprise_invocations ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_enterprise_nonces ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_execution_budgets ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_execution_evidence ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_execution_leases ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_fleet ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_idempotency ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_insights_admissions ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_insights_attempts ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_insights_cells ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_insights_checkpoints ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_insights_facts ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_insights_gateway_epochs ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_insights_invocations ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_insights_spans ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_insights_tenant_state ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_invocation_authority ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_invocation_event_streams ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_invocation_events ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_invocation_results ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_mcp_approvals ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_mcp_requests ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_mcp_scans ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_mcp_servers ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_mcp_snapshots ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_operation_attempts ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_operation_cancellations ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_operation_outcomes ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_operation_recoveries ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_operation_review_owners ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_operations ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_outcome_reconciliations ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_package_provisioning ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_policy_bundles ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_queued_authority ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_record_ids ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_records ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_recovery_floors ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_reviewed_recovery ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_secrets ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_server_deployments ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_server_documents ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_server_packages ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_server_settings ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_tenant_authority ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_tenant_capacity ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_tenant_identity_keys ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_tenant_initializations ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_tenant_runtime_profiles ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_tenant_vault_keys ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_tool_host_dispatches ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.control_tool_hosts ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_access ON public.control_approval_clocks USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_attempt_effects USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_attempt_manifest_seals USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_attempt_recovery_scopes USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_attempt_reviews USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_attempt_settlements USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_attempt_snapshots USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_audit USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_audit_anchors USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_authorization_outbox USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_bootstrap_admins USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_builder USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_business_key_history USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_business_keys USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_configuration_changes USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_console_devices USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_credential_history USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_effect_consent_approvals USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_effect_consent_revocations USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_effect_consent_slots USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_effect_consent_uses USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_effect_consents USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_effect_evidence USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_effect_generations USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_effect_intents USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_effect_target_bindings USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_effects USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_endpoint_configurations USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_endpoints USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_enrollments USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_enterprise_adoptions USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_enterprise_approvals USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_enterprise_invocations USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_enterprise_nonces USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_execution_budgets USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_execution_evidence USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_execution_leases USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_fleet USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_idempotency USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_insights_admissions USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_insights_attempts USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_insights_cells USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_insights_checkpoints USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_insights_facts USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_insights_gateway_epochs USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_insights_invocations USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_insights_spans USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_insights_tenant_state USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_invocation_authority USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_invocation_event_streams USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_invocation_events USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_invocation_results USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_mcp_approvals USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_mcp_requests USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_mcp_scans USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_mcp_servers USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_mcp_snapshots USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_operation_attempts USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_operation_cancellations USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_operation_outcomes USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_operation_recoveries USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_operation_review_owners USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_operations USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_outcome_reconciliations USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_package_provisioning USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_policy_bundles USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_queued_authority USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_record_ids USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_records USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_recovery_floors USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_reviewed_recovery USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_secrets USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_server_deployments USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_server_documents USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_server_packages USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_server_settings USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_tenant_authority USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_tenant_capacity USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_tenant_identity_keys USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_tenant_initializations USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_tenant_runtime_profiles USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_tenant_vault_keys USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_tool_host_dispatches USING (true) WITH CHECK (true);

CREATE POLICY tenant_access ON public.control_tool_hosts USING (true) WITH CHECK (true);

CREATE POLICY tenant_fence ON public.control_approval_clocks AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_attempt_effects AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_attempt_manifest_seals AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_attempt_recovery_scopes AS RESTRICTIVE USING (((tenant_id = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK (((tenant_id = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_attempt_reviews AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_attempt_settlements AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_attempt_snapshots AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_audit AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_audit_anchors AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_authorization_outbox AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_bootstrap_admins AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_builder AS RESTRICTIVE USING (((tenant_id = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK (((tenant_id = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_business_key_history AS RESTRICTIVE USING (((tenant_id = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK (((tenant_id = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_business_keys AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_configuration_changes AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_console_devices AS RESTRICTIVE USING (((tenant_id = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK (((tenant_id = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_credential_history AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_effect_consent_approvals AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_effect_consent_revocations AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_effect_consent_slots AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_effect_consent_uses AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_effect_consents AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_effect_evidence AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_effect_generations AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_effect_intents AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_effect_target_bindings AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_effects AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_endpoint_configurations AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_endpoints AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_enrollments AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_enterprise_adoptions AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_enterprise_approvals AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_enterprise_invocations AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_enterprise_nonces AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_execution_budgets AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_execution_evidence AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_execution_leases AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_fleet AS RESTRICTIVE USING (((tenant_id = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK (((tenant_id = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_idempotency AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_insights_admissions AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_insights_attempts AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_insights_cells AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_insights_checkpoints AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_insights_facts AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_insights_gateway_epochs AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_insights_invocations AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_insights_spans AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_insights_tenant_state AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_invocation_authority AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_invocation_event_streams AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_invocation_events AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_invocation_results AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_mcp_approvals AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_mcp_requests AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_mcp_scans AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_mcp_servers AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_mcp_snapshots AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_operation_attempts AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_operation_cancellations AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_operation_outcomes AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_operation_recoveries AS RESTRICTIVE USING (((tenant_id = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK (((tenant_id = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_operation_review_owners AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_operations AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_outcome_reconciliations AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_package_provisioning AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_policy_bundles AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_queued_authority AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_record_ids AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_records AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_recovery_floors AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_reviewed_recovery AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_secrets AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_server_deployments AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_server_documents AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_server_packages AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_server_settings AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_tenant_authority AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_tenant_capacity AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_tenant_identity_keys AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_tenant_initializations AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_tenant_runtime_profiles AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_tenant_vault_keys AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_tool_host_dispatches AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

CREATE POLICY tenant_fence ON public.control_tool_hosts AS RESTRICTIVE USING ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK ((((tenant_id)::text = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));

GRANT USAGE ON SCHEMA public TO toolgate_control_runtime;
GRANT USAGE ON SCHEMA public TO toolgate_control_platform;

REVOKE ALL ON FUNCTION public.consent_approvals_capacity() FROM PUBLIC;

REVOKE ALL ON FUNCTION public.consent_revocations_capacity() FROM PUBLIC;

REVOKE ALL ON FUNCTION public.consent_slots_capacity() FROM PUBLIC;

REVOKE ALL ON FUNCTION public.consent_uses_capacity() FROM PUBLIC;

REVOKE ALL ON FUNCTION public.consents_capacity() FROM PUBLIC;

REVOKE ALL ON FUNCTION public.control_attempt_effects_capacity() FROM PUBLIC;

REVOKE ALL ON FUNCTION public.control_attempt_manifest_seals_capacity() FROM PUBLIC;

REVOKE ALL ON FUNCTION public.control_attempt_review_capacity() FROM PUBLIC;

REVOKE ALL ON FUNCTION public.control_attempt_settlement_capacity() FROM PUBLIC;





REVOKE ALL ON FUNCTION public.control_effect_intent_capacity() FROM PUBLIC;

















REVOKE ALL ON FUNCTION public.control_operation_outcome_capacity() FROM PUBLIC;

REVOKE ALL ON FUNCTION public.control_operation_recovery_capacity() FROM PUBLIC;



REVOKE ALL ON FUNCTION public.control_recovery_scope_capacity() FROM PUBLIC;

REVOKE ALL ON FUNCTION public.control_review_owners_capacity() FROM PUBLIC;







REVOKE ALL ON FUNCTION public.control_tenant_access(write_lock boolean) FROM PUBLIC;
GRANT ALL ON FUNCTION public.control_tenant_access(write_lock boolean) TO toolgate_control_runtime;
GRANT ALL ON FUNCTION public.control_tenant_access(write_lock boolean) TO toolgate_control_platform;

REVOKE ALL ON FUNCTION public.control_tenant_admit(tenant text, write_lock boolean) FROM PUBLIC;
GRANT ALL ON FUNCTION public.control_tenant_admit(tenant text, write_lock boolean) TO toolgate_control_runtime;
GRANT ALL ON FUNCTION public.control_tenant_admit(tenant text, write_lock boolean) TO toolgate_control_platform;



REVOKE ALL ON FUNCTION public.effect_target_binding_capacity() FROM PUBLIC;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_approval_clocks TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_approval_clocks TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_attempt_effects TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_attempt_effects TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_attempt_manifest_seals TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_attempt_manifest_seals TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_attempt_recovery_scopes TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_attempt_recovery_scopes TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_attempt_reviews TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_attempt_reviews TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_attempt_settlements TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_attempt_settlements TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_attempt_snapshots TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_attempt_snapshots TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_audit TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_audit TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_audit_anchors TO toolgate_control_runtime;

GRANT USAGE ON SEQUENCE public.control_audit_sequence_seq TO toolgate_control_runtime;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_authorization_outbox TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_authorization_outbox TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_bootstrap_admins TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_bootstrap_admins TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_builder TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_builder TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_business_key_history TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_business_key_history TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_business_keys TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_business_keys TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_configuration_changes TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_configuration_changes TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_console_devices TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_console_devices TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_credential_history TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_credential_history TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_effect_consent_approvals TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_effect_consent_approvals TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_effect_consent_revocations TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_effect_consent_revocations TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_effect_consent_slots TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_effect_consent_slots TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_effect_consent_uses TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_effect_consent_uses TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_effect_consents TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_effect_consents TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_effect_evidence TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_effect_evidence TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_effect_generations TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_effect_generations TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_effect_intents TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_effect_intents TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_effect_target_bindings TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_effect_target_bindings TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_effects TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_effects TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_endpoint_configurations TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_endpoint_configurations TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_endpoints TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_endpoints TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_enrollments TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_enrollments TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_enterprise_adoptions TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_enterprise_adoptions TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_enterprise_approvals TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_enterprise_approvals TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_enterprise_invocations TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_enterprise_invocations TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_enterprise_nonces TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_enterprise_nonces TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_execution_budgets TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_execution_budgets TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_execution_evidence TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_execution_evidence TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_execution_leases TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_execution_leases TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_fleet TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_fleet TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE ON TABLE public.control_idempotency TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE ON TABLE public.control_idempotency TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_admissions TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_admissions TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_attempts TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_attempts TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_cells TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_cells TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_checkpoints TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_checkpoints TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_facts TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_facts TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_gateway_epochs TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_gateway_epochs TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_invocations TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_invocations TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_spans TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_spans TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_tenant_state TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_insights_tenant_state TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_invocation_authority TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_invocation_authority TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_invocation_event_streams TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_invocation_event_streams TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_invocation_events TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_invocation_events TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_invocation_results TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_invocation_results TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_mcp_approvals TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_mcp_approvals TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_mcp_requests TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_mcp_requests TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_mcp_scans TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_mcp_scans TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_mcp_servers TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_mcp_servers TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE ON TABLE public.control_mcp_snapshots TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE ON TABLE public.control_mcp_snapshots TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_operation_attempts TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_operation_attempts TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_operation_cancellations TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_operation_cancellations TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_operation_outcomes TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_operation_outcomes TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_operation_recoveries TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_operation_recoveries TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_operation_review_owners TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_operation_review_owners TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_operations TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_operations TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_outcome_reconciliations TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_outcome_reconciliations TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_package_provisioning TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_package_provisioning TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_policy_bundles TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_policy_bundles TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_queued_authority TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_queued_authority TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_record_ids TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_record_ids TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_records TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_records TO toolgate_control_platform;

GRANT SELECT ON TABLE public.control_recovery_floors TO toolgate_control_runtime;
GRANT SELECT ON TABLE public.control_recovery_floors TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_reviewed_recovery TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_reviewed_recovery TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_secrets TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_secrets TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_server_deployments TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_server_deployments TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_server_documents TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_server_documents TO toolgate_control_platform;

GRANT SELECT,INSERT ON TABLE public.control_server_packages TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_server_packages TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_server_settings TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_server_settings TO toolgate_control_platform;

GRANT SELECT,UPDATE ON TABLE public.control_tenant_authority TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_tenant_authority TO toolgate_control_platform;




GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_tenant_hosts TO toolgate_control_platform;

GRANT SELECT ON TABLE public.control_tenant_identity_keys TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_tenant_identity_keys TO toolgate_control_platform;

GRANT SELECT ON TABLE public.control_tenant_initializations TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_tenant_initializations TO toolgate_control_platform;

GRANT SELECT ON TABLE public.control_tenant_runtime_profiles TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_tenant_runtime_profiles TO toolgate_control_platform;

GRANT SELECT ON TABLE public.control_tenant_vault_keys TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_tenant_vault_keys TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_tenants TO toolgate_control_platform;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_tool_host_dispatches TO toolgate_control_runtime;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.control_tool_host_dispatches TO toolgate_control_platform;

GRANT SELECT,INSERT,UPDATE ON TABLE public.control_tool_hosts TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_tool_hosts TO toolgate_control_platform;
SELECT pg_catalog.set_config('search_path', 'public', false);
SET check_function_bodies = true;


-- Current read-only lookup witnesses are append-only effect evidence, never execution authority.
CREATE FUNCTION public.control_lookup_witness_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF substr(NEW.source,1,14)='TARGET_LOOKUP:' AND (
 length(NEW.source)<>78 OR NEW.source !~ '^TARGET_LOOKUP:[a-f0-9]{64}$'
 OR NOT EXISTS(SELECT 1 FROM control_effect_generations g
 JOIN control_effect_target_bindings b ON b.tenant_id=g.tenant_id AND b.effect_id=g.effect_id
 JOIN control_enterprise_invocations i ON i.tenant_id=g.tenant_id AND i.invocation_id=g.operation_id
 WHERE g.tenant_id=NEW.tenant_id AND g.effect_id=NEW.effect_id AND g.generation=NEW.generation AND g.state='AMBIGUOUS'
 AND i.state='OUTCOME_UNKNOWN' AND b.document::jsonb->>'statusRouteId' IS NOT NULL
 AND NOT EXISTS(SELECT 1 FROM control_effect_generations active WHERE active.tenant_id=g.tenant_id AND active.effect_id=g.effect_id
 AND active.state NOT IN('AMBIGUOUS','RELEASED')))
 OR ((SELECT count(*) FROM control_effect_evidence w WHERE w.tenant_id=NEW.tenant_id AND w.effect_id=NEW.effect_id
 AND w.generation=NEW.generation AND substr(w.source,1,14)='TARGET_LOOKUP:')>=8
 AND NOT EXISTS(SELECT 1 FROM control_effect_evidence w WHERE w.tenant_id=NEW.tenant_id AND w.effect_id=NEW.effect_id
 AND w.generation=NEW.generation AND w.evidence_digest=NEW.evidence_digest))
 ) THEN RAISE EXCEPTION 'invalid target lookup witness'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER control_lookup_witness_guard BEFORE INSERT ON public.control_effect_evidence
FOR EACH ROW EXECUTE FUNCTION public.control_lookup_witness_guard();

CREATE FUNCTION public.control_lookup_resend_fence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM control_effect_evidence w WHERE w.tenant_id=OLD.tenant_id AND w.effect_id=OLD.effect_id
 AND substr(w.source,1,14)='TARGET_LOOKUP:') THEN RAISE EXCEPTION 'witnessed target outcome fences resend'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER control_lookup_resend_fence BEFORE UPDATE ON public.control_effects
FOR EACH ROW WHEN (NEW.current_generation<>OLD.current_generation) EXECUTE FUNCTION public.control_lookup_resend_fence();

CREATE FUNCTION public.control_lookup_recovery_fence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.proof='TARGET_DEDUPLICATION' AND EXISTS(
 SELECT 1 FROM control_effect_generations g JOIN control_effect_evidence w ON w.tenant_id=g.tenant_id AND w.effect_id=g.effect_id
 WHERE g.tenant_id=NEW.tenant_id AND g.operation_id=NEW.operation_id AND substr(w.source,1,14)='TARGET_LOOKUP:'
 ) THEN RAISE EXCEPTION 'witnessed target outcome fences recovery'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER control_lookup_recovery_fence BEFORE INSERT ON public.control_operation_recoveries
FOR EACH ROW EXECUTE FUNCTION public.control_lookup_recovery_fence();

-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- Current fresh-schema kill storage, mirrored in initial V1.
-- Grow-only signed kill and recovery events (plan.md §15). Normative description:
-- docs/control-plane/kill-switch.md.

CREATE TABLE control_kill_events (
    tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
    event_id varchar(40) NOT NULL,
    issuer varchar(24) NOT NULL CHECK (issuer IN ('control','control-breakglass')),
    issuer_counter bigint NOT NULL CHECK (issuer_counter BETWEEN 1 AND 9999999999999999),
    event_kind varchar(8) NOT NULL CHECK (event_kind IN ('KILL','RECOVERY')),
    scope_kind varchar(16) CHECK (scope_kind IN ('PACKAGE','PACKAGE_DIGEST','PUBLISHER','TOOL','POOL','RUNTIME_IMAGE','TENANT')),
    scope_value varchar(256),
    mode varchar(16) CHECK (mode IN ('STOP_NEW','TERMINATE')),
    authorization_epoch bigint NOT NULL CHECK (authorization_epoch BETWEEN 1 AND 9007199254740991),
    issued_at bigint NOT NULL CHECK (issued_at BETWEEN 0 AND 9007199254740991),
    document text NOT NULL CHECK (octet_length(document) <= 65536),
    PRIMARY KEY (tenant_id, event_id),
    UNIQUE (tenant_id, issuer, issuer_counter),
    UNIQUE (tenant_id, authorization_epoch),
    CHECK (event_id = issuer || ':' || issuer_counter::text),
    CHECK (event_kind = 'KILL' OR issuer = 'control'),
    CHECK ((event_kind = 'KILL') = (scope_kind IS NOT NULL)),
    CHECK ((event_kind = 'KILL') = (mode IS NOT NULL)),
    CHECK (scope_kind IS NULL OR (scope_kind = 'TENANT') = (scope_value IS NULL))
);
CREATE INDEX control_kill_event_epoch ON control_kill_events (tenant_id, authorization_epoch);

-- One row per (recovery, kill) it supersedes; a kill is effective while it has no row here.
CREATE TABLE control_kill_supersessions (
    tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
    kill_event_id varchar(40) NOT NULL,
    recovery_event_id varchar(40) NOT NULL,
    PRIMARY KEY (tenant_id, kill_event_id, recovery_event_id),
    FOREIGN KEY (tenant_id, kill_event_id) REFERENCES control_kill_events (tenant_id, event_id),
    FOREIGN KEY (tenant_id, recovery_event_id) REFERENCES control_kill_events (tenant_id, event_id)
);

-- Superseded events move out of the push set into the audited archive; the event rows stay.
CREATE TABLE control_kill_archive (
    tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
    event_id varchar(40) NOT NULL,
    archived_at bigint NOT NULL CHECK (archived_at BETWEEN 0 AND 9007199254740991),
    PRIMARY KEY (tenant_id, event_id),
    FOREIGN KEY (tenant_id, event_id) REFERENCES control_kill_events (tenant_id, event_id)
);

-- Highest authorization epoch each component has acknowledged, and the path that delivered it.
CREATE TABLE control_kill_acks (
    tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
    component_kind varchar(16) NOT NULL CHECK (component_kind IN ('TOOL_HOST','ENDPOINT','GATEWAY')),
    component_id varchar(256) NOT NULL,
    acked_epoch bigint NOT NULL CHECK (acked_epoch BETWEEN 0 AND 9007199254740991),
    acked_at bigint NOT NULL CHECK (acked_at BETWEEN 0 AND 9007199254740991),
    delivery_path varchar(16) NOT NULL CHECK (delivery_path IN ('PUSH','CHECK_IN','POLL')),
    PRIMARY KEY (tenant_id, component_kind, component_id)
);

CREATE FUNCTION control_kill_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'kill events are grow-only'; END $$;
CREATE TRIGGER control_kill_events_immutable BEFORE UPDATE OR DELETE ON control_kill_events
    FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();
CREATE TRIGGER control_kill_supersessions_immutable BEFORE UPDATE OR DELETE ON control_kill_supersessions
    FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();
CREATE TRIGGER control_kill_archive_immutable BEFORE UPDATE OR DELETE ON control_kill_archive
    FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();

-- Only a recovery event may supersede, only a kill may be superseded, and only an
-- already superseded event may be archived.
CREATE FUNCTION control_kill_supersession_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM control_kill_events k JOIN control_kill_events r ON r.tenant_id=k.tenant_id
        WHERE k.tenant_id=NEW.tenant_id AND k.event_id=NEW.kill_event_id AND k.event_kind='KILL'
        AND r.event_id=NEW.recovery_event_id AND r.event_kind='RECOVERY' AND r.authorization_epoch>k.authorization_epoch
        AND (r.document::jsonb->'supersedes') @> jsonb_build_array(k.event_id)) THEN
        RAISE EXCEPTION 'a signed later recovery must name its superseded kill';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER control_kill_supersession_guard BEFORE INSERT ON control_kill_supersessions
    FOR EACH ROW EXECUTE FUNCTION control_kill_supersession_guard();

CREATE FUNCTION control_kill_archive_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM control_kill_supersessions WHERE tenant_id = NEW.tenant_id
                   AND (kill_event_id = NEW.event_id OR recovery_event_id = NEW.event_id)) THEN
        RAISE EXCEPTION 'only superseded kills and applied recoveries are archived';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER control_kill_archive_guard BEFORE INSERT ON control_kill_archive
    FOR EACH ROW EXECUTE FUNCTION control_kill_archive_guard();

-- Acknowledgements only move forward.
CREATE FUNCTION control_kill_ack_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.tenant_id <> OLD.tenant_id OR NEW.component_kind <> OLD.component_kind OR NEW.component_id <> OLD.component_id OR NEW.acked_epoch < OLD.acked_epoch OR NEW.acked_at < OLD.acked_at THEN RAISE EXCEPTION 'kill acknowledgements are monotonic'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER control_kill_ack_guard BEFORE UPDATE OR DELETE ON control_kill_acks
    FOR EACH ROW EXECUTE FUNCTION control_kill_ack_guard();

GRANT SELECT, INSERT ON control_kill_events, control_kill_supersessions, control_kill_archive TO toolgate_control_runtime;
GRANT SELECT, INSERT, UPDATE ON control_kill_acks TO toolgate_control_runtime;

ALTER TABLE control_kill_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_events USING (true) WITH CHECK (true);
CREATE POLICY tenant_fence ON control_kill_events AS RESTRICTIVE USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_kill_events TO toolgate_control_platform;
ALTER TABLE control_kill_supersessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_supersessions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_supersessions USING (true) WITH CHECK (true);
CREATE POLICY tenant_fence ON control_kill_supersessions AS RESTRICTIVE USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_kill_supersessions TO toolgate_control_platform;
ALTER TABLE control_kill_archive ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_archive FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_archive USING (true) WITH CHECK (true);
CREATE POLICY tenant_fence ON control_kill_archive AS RESTRICTIVE USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_kill_archive TO toolgate_control_platform;
ALTER TABLE control_kill_acks ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_acks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_acks USING (true) WITH CHECK (true);
CREATE POLICY tenant_fence ON control_kill_acks AS RESTRICTIVE USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT,UPDATE ON control_kill_acks TO toolgate_control_platform;

CREATE TABLE control_invocation_kill_scopes(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),
 operation_id TEXT NOT NULL,attempt_id TEXT NOT NULL,device_id TEXT NOT NULL,
 package_id TEXT,package_digest TEXT NOT NULL,tool_id TEXT NOT NULL,
 publisher_id TEXT,pool_id TEXT,image_digest TEXT,
 PRIMARY KEY(tenant_id,attempt_id),
 FOREIGN KEY(tenant_id,operation_id,attempt_id) REFERENCES control_operation_attempts(tenant_id,operation_id,attempt_id),
 CHECK(length(operation_id) BETWEEN 1 AND 128),CHECK(length(attempt_id) BETWEEN 1 AND 128),
 CHECK(length(device_id) BETWEEN 1 AND 128),CHECK(length(tool_id) BETWEEN 1 AND 128),
 CHECK(length(package_digest)=71),CHECK(package_id IS NULL OR length(package_id) BETWEEN 1 AND 128),
 CHECK(publisher_id IS NULL OR length(publisher_id) BETWEEN 1 AND 128),
 CHECK(pool_id IS NULL OR length(pool_id)<=128),CHECK(image_digest IS NULL OR length(image_digest) IN(0,71))
);
CREATE TRIGGER control_invocation_kill_scopes_immutable BEFORE UPDATE OR DELETE ON control_invocation_kill_scopes FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();
ALTER TABLE control_invocation_kill_scopes ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_invocation_kill_scopes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_invocation_kill_scopes USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_invocation_kill_scopes AS RESTRICTIVE USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_invocation_kill_scopes TO toolgate_control_runtime,toolgate_control_platform;

CREATE TABLE control_kill_proposals(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),proposal_id TEXT NOT NULL,requester_id TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN('KILL','RECOVERY')),authorization_epoch BIGINT NOT NULL CHECK(authorization_epoch BETWEEN 0 AND 9007199254740991),
 request_digest TEXT NOT NULL CHECK(length(request_digest)=64),document TEXT NOT NULL CHECK(octet_length(document)<=65536 AND jsonb_typeof(document::jsonb)='object'),
 created_at BIGINT NOT NULL CHECK(created_at BETWEEN 0 AND 9007199254740991),
 expires_at BIGINT NOT NULL CHECK(expires_at>created_at AND expires_at<=created_at+600000),
 PRIMARY KEY(tenant_id,proposal_id),CHECK(length(proposal_id) BETWEEN 1 AND 128),CHECK(length(requester_id) BETWEEN 1 AND 128)
);
CREATE TABLE control_kill_reviews(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),proposal_id TEXT NOT NULL,reviewer_id TEXT NOT NULL,
 approved BOOLEAN NOT NULL,reviewed_at BIGINT NOT NULL CHECK(reviewed_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,proposal_id,reviewer_id),
 FOREIGN KEY(tenant_id,proposal_id) REFERENCES control_kill_proposals(tenant_id,proposal_id)
);
CREATE TABLE control_kill_closures(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),proposal_id TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN('APPLIED','REJECTED')),
 event_id TEXT,closed_at BIGINT NOT NULL CHECK(closed_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,proposal_id),
 FOREIGN KEY(tenant_id,proposal_id) REFERENCES control_kill_proposals(tenant_id,proposal_id),
 FOREIGN KEY(tenant_id,event_id) REFERENCES control_kill_events(tenant_id,event_id),
 CHECK((state='APPLIED')=(event_id IS NOT NULL))
);
CREATE TRIGGER control_kill_proposals_immutable BEFORE UPDATE OR DELETE ON control_kill_proposals FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();
ALTER TABLE control_kill_proposals ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_proposals FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_proposals USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_kill_proposals AS RESTRICTIVE USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_kill_proposals TO toolgate_control_runtime,toolgate_control_platform;
CREATE TRIGGER control_kill_reviews_immutable BEFORE UPDATE OR DELETE ON control_kill_reviews FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();
ALTER TABLE control_kill_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_reviews FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_reviews USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_kill_reviews AS RESTRICTIVE USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_kill_reviews TO toolgate_control_runtime,toolgate_control_platform;
CREATE TRIGGER control_kill_closures_immutable BEFORE UPDATE OR DELETE ON control_kill_closures FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();
ALTER TABLE control_kill_closures ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_closures FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_closures USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_kill_closures AS RESTRICTIVE USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_kill_closures TO toolgate_control_runtime,toolgate_control_platform;
CREATE FUNCTION control_kill_review_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NOT EXISTS(SELECT 1 FROM control_kill_proposals p WHERE p.tenant_id=NEW.tenant_id AND p.proposal_id=NEW.proposal_id
 AND p.requester_id<>NEW.reviewer_id AND p.created_at<=NEW.reviewed_at AND p.expires_at>NEW.reviewed_at)
 OR EXISTS(SELECT 1 FROM control_kill_closures c WHERE c.tenant_id=NEW.tenant_id AND c.proposal_id=NEW.proposal_id)
 OR (SELECT COUNT(*) FROM control_kill_reviews r WHERE r.tenant_id=NEW.tenant_id AND r.proposal_id=NEW.proposal_id)>=2 THEN RAISE EXCEPTION 'independent current kill review required'; END IF; RETURN NEW; END $$;
CREATE TRIGGER control_kill_review_guard BEFORE INSERT ON control_kill_reviews FOR EACH ROW EXECUTE FUNCTION control_kill_review_guard();
CREATE FUNCTION control_kill_closure_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NOT EXISTS(SELECT 1 FROM control_kill_proposals p WHERE p.tenant_id=NEW.tenant_id AND p.proposal_id=NEW.proposal_id
 AND p.created_at<=NEW.closed_at AND p.expires_at>NEW.closed_at)
 OR (NEW.state='APPLIED' AND ((SELECT COUNT(*) FROM control_kill_reviews r WHERE r.tenant_id=NEW.tenant_id AND r.proposal_id=NEW.proposal_id AND r.approved=true)<>2
 OR NOT EXISTS(SELECT 1 FROM control_kill_events e JOIN control_kill_proposals p ON p.tenant_id=e.tenant_id AND p.kind=e.event_kind
 WHERE e.tenant_id=NEW.tenant_id AND e.event_id=NEW.event_id AND p.proposal_id=NEW.proposal_id AND e.issuer='control')))
 OR (NEW.state='REJECTED' AND NOT EXISTS(SELECT 1 FROM control_kill_reviews r WHERE r.tenant_id=NEW.tenant_id AND r.proposal_id=NEW.proposal_id AND r.approved=false)) THEN RAISE EXCEPTION 'independent current kill review required'; END IF; RETURN NEW; END $$;
CREATE TRIGGER control_kill_closure_guard BEFORE INSERT ON control_kill_closures FOR EACH ROW EXECUTE FUNCTION control_kill_closure_guard();
CREATE TABLE control_kill_breakglass_requests(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),request_id TEXT NOT NULL,
 request_digest TEXT NOT NULL CHECK(length(request_digest)=64),event_id TEXT NOT NULL,
 certificate_sha256 TEXT NOT NULL CHECK(length(certificate_sha256)=64),
 not_after BIGINT NOT NULL CHECK(not_after BETWEEN 1 AND 9007199254740991),
 applied_at BIGINT NOT NULL CHECK(applied_at>=0 AND applied_at<not_after),
 PRIMARY KEY(tenant_id,request_id),UNIQUE(tenant_id,event_id),
 FOREIGN KEY(tenant_id,event_id) REFERENCES control_kill_events(tenant_id,event_id),
 CHECK(length(request_id) BETWEEN 1 AND 128)
);
CREATE TRIGGER control_kill_breakglass_requests_immutable BEFORE UPDATE OR DELETE ON control_kill_breakglass_requests FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();
ALTER TABLE control_kill_breakglass_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_breakglass_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_breakglass_requests USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_kill_breakglass_requests AS RESTRICTIVE USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_kill_breakglass_requests TO toolgate_control_runtime,toolgate_control_platform;
CREATE FUNCTION control_kill_breakglass_request_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NOT EXISTS(
 SELECT 1 FROM control_kill_events e WHERE e.tenant_id=NEW.tenant_id AND e.event_id=NEW.event_id AND e.event_kind='KILL'
 AND e.issuer='control-breakglass' AND e.issued_at=NEW.applied_at)
 THEN RAISE EXCEPTION 'operator request requires its committed emergency kill'; END IF; RETURN NEW; END $$;
CREATE TRIGGER control_kill_breakglass_request_guard BEFORE INSERT ON control_kill_breakglass_requests FOR EACH ROW EXECUTE FUNCTION control_kill_breakglass_request_guard();

CREATE TABLE control_kill_gateways(
 tenant_id TEXT NOT NULL REFERENCES control_tenants(tenant_id),gateway_id TEXT NOT NULL,
 registered_at BIGINT NOT NULL CHECK(registered_at BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(tenant_id,gateway_id),CHECK(length(gateway_id)=64)
);
CREATE TRIGGER control_kill_gateways_immutable BEFORE UPDATE OR DELETE ON control_kill_gateways FOR EACH ROW EXECUTE FUNCTION control_kill_immutable();
ALTER TABLE control_kill_gateways ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_kill_gateways FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_kill_gateways USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_kill_gateways AS RESTRICTIVE USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(false)) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'') AND control_tenant_access(true));
GRANT SELECT,INSERT ON control_kill_gateways TO toolgate_control_runtime,toolgate_control_platform;
-- BEGIN TRIGGERS (issue #274, ADR 058): assignments, instances, desired documents and run leases in one tenant-scoped
-- document table. Rows are never deleted: a removed assignment or instance keeps its row with a tombstone state, and
-- every update advances the revision by exactly one (compare-and-swap under the tenant's transaction).
CREATE FUNCTION public.control_triggers_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    -- Retention purges only (plan 12.1): events, dedupe tombstones, deliveries, cursors and tickets.
    IF OLD.kind <> ALL (ARRAY['OUTBOX'::text, 'LOG'::text, 'EVENT'::text, 'KEY'::text, 'DELIVERY'::text, 'DUE'::text, 'EXPIRY'::text, 'CURSOR'::text, 'TICKET'::text]) THEN
      RAISE EXCEPTION 'trigger record identity or revision violation';
    END IF;
    RETURN OLD;
  END IF;
  IF NEW.tenant_id <> OLD.tenant_id OR NEW.kind <> OLD.kind OR NEW.record_id <> OLD.record_id
      OR NEW.revision <> OLD.revision + 1 THEN
    RAISE EXCEPTION 'trigger record identity or revision violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TABLE public.control_triggers (
    tenant_id text NOT NULL,
    kind text NOT NULL,
    record_id text NOT NULL,
    revision bigint NOT NULL,
    document text NOT NULL,
    CONSTRAINT control_triggers_document_check CHECK ((octet_length(document) <= 131072)),
    CONSTRAINT control_triggers_kind_check CHECK ((kind = ANY (ARRAY['ASSIGNMENT'::text, 'INSTANCE'::text, 'DESIRED'::text, 'LEASE'::text, 'EVENT'::text, 'KEY'::text, 'EPOCH'::text, 'CHECKPOINT'::text, 'OUTBOX'::text, 'ROUTER'::text, 'LOG'::text, 'DELIVERY'::text, 'DUE'::text, 'EXPIRY'::text, 'SETTINGS'::text, 'SUBSCRIPTION'::text, 'CURSOR'::text, 'TICKET'::text]))),
    CONSTRAINT control_triggers_record_check CHECK (((length(record_id) >= 1) AND (length(record_id) <= 256))),
    CONSTRAINT control_triggers_revision_check CHECK (((revision > 0) AND (revision <= '9007199254740991'::bigint))),
    CONSTRAINT control_triggers_pkey PRIMARY KEY (tenant_id, kind, record_id),
    CONSTRAINT control_triggers_tenant_owner FOREIGN KEY (tenant_id) REFERENCES public.control_tenants(tenant_id)
);

ALTER TABLE ONLY public.control_triggers FORCE ROW LEVEL SECURITY;
ALTER TABLE public.control_triggers ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON public.control_triggers USING (true) WITH CHECK (true);
CREATE POLICY tenant_fence ON public.control_triggers AS RESTRICTIVE USING (((tenant_id = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(false))) WITH CHECK (((tenant_id = NULLIF(current_setting('app.tenant_id'::text, true), ''::text)) AND public.control_tenant_access(true)));
CREATE TRIGGER control_triggers_guard BEFORE DELETE OR UPDATE ON public.control_triggers FOR EACH ROW EXECUTE FUNCTION public.control_triggers_guard();
CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON public.control_triggers FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();
GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE public.control_triggers TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_triggers TO toolgate_control_platform;
-- END TRIGGERS

CREATE TABLE control_workflow_profile_revisions(tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),profile_id varchar(128) NOT NULL,digest varchar(71) NOT NULL CHECK(length(digest)=71 AND substr(digest,1,7)='sha256:'),document text NOT NULL,key_version bigint NOT NULL CHECK(key_version>=1),state varchar(16) NOT NULL CHECK(state IN('DRAFT','REVIEWED')),reviewed_by varchar(128),CHECK((state='DRAFT' AND reviewed_by IS NULL) OR (state='REVIEWED' AND reviewed_by IS NOT NULL)),CHECK(octet_length(document)<=393216),FOREIGN KEY(tenant_id,key_version) REFERENCES control_tenant_vault_keys(tenant_id,version),PRIMARY KEY(tenant_id,profile_id,digest));
ALTER TABLE control_workflow_profile_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_workflow_profile_revisions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_workflow_profile_revisions USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_workflow_profile_revisions AS RESTRICTIVE USING(tenant_id::text=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false)) WITH CHECK(tenant_id::text=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON control_workflow_profile_revisions FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_workflow_profile_revisions TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_workflow_profile_revisions TO toolgate_control_platform;
CREATE TABLE control_workflow_profile_heads(tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),profile_id varchar(128) NOT NULL,digest varchar(71) NOT NULL,generation bigint NOT NULL CHECK(generation BETWEEN 1 AND 9007199254740991),FOREIGN KEY(tenant_id,profile_id,digest) REFERENCES control_workflow_profile_revisions(tenant_id,profile_id,digest),PRIMARY KEY(tenant_id,profile_id));
ALTER TABLE control_workflow_profile_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_workflow_profile_heads FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_workflow_profile_heads USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_workflow_profile_heads AS RESTRICTIVE USING(tenant_id::text=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false)) WITH CHECK(tenant_id::text=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON control_workflow_profile_heads FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_workflow_profile_heads TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_workflow_profile_heads TO toolgate_control_platform;
CREATE TABLE control_workflow_profile_owners(tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),resource_kind varchar(32) NOT NULL CHECK(resource_kind IN('WORKFLOW_GROUP','MODEL_GROUP','AGENT_DEFINITION','WORKFLOW','DISPATCH','MODEL_DEPLOYMENT','WORKFLOW_VERSION','DISPATCH_SOURCE')),resource_id varchar(128) NOT NULL,profile_id varchar(128) NOT NULL,definition_digest varchar(71),PRIMARY KEY(tenant_id,resource_kind,resource_id));
ALTER TABLE control_workflow_profile_owners ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_workflow_profile_owners FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_workflow_profile_owners USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_workflow_profile_owners AS RESTRICTIVE USING(tenant_id::text=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false)) WITH CHECK(tenant_id::text=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON control_workflow_profile_owners FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();
GRANT SELECT,INSERT ON TABLE public.control_workflow_profile_owners TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_workflow_profile_owners TO toolgate_control_platform;
CREATE FUNCTION control_workflow_revision_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.profile_id IS DISTINCT FROM OLD.profile_id OR NEW.digest IS DISTINCT FROM OLD.digest OR NEW.document IS DISTINCT FROM OLD.document OR NEW.key_version IS DISTINCT FROM OLD.key_version OR OLD.state<>'DRAFT' OR NEW.state<>'REVIEWED' OR OLD.reviewed_by IS NOT NULL OR NEW.reviewed_by IS NULL THEN
 RAISE EXCEPTION 'immutable workflow profile revision'; END IF; RETURN NEW;
END;$$;
CREATE TRIGGER workflow_revision_immutable BEFORE UPDATE ON control_workflow_profile_revisions FOR EACH ROW EXECUTE FUNCTION control_workflow_revision_guard();

CREATE TABLE control_workflow_runs(
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
 run_id varchar(128) NOT NULL, profile_id varchar(128) NOT NULL, profile_digest varchar(71) NOT NULL,
 workflow_id varchar(128) NOT NULL, workflow_version_id varchar(128) NOT NULL,
 service_agent_id varchar(128) NOT NULL, service_agent_group_id varchar(128) NOT NULL,
 remediation_mode varchar(16) NOT NULL CHECK(remediation_mode IN('PREPARE','APPLY')),
 state varchar(24) NOT NULL CHECK(state IN('QUEUED','RUNNING','SUCCEEDED','FAILED','AWAITING_APPROVAL','CANCELLED','EXPIRED')),
 document text NOT NULL CHECK(octet_length(document)<=65536), created_at bigint NOT NULL CHECK(created_at>=0),
 key_version bigint NOT NULL,
 lease_id varchar(128), lease_owner varchar(128), lease_token_digest varchar(64), lease_expires_at bigint,
 step_kind varchar(16) CHECK(step_kind IN('WORKFLOW','AGENT')), step_subject varchar(128), step_model_group varchar(128), step_token_digest varchar(64),
 CHECK((step_kind IS NULL AND step_subject IS NULL AND step_model_group IS NULL AND step_token_digest IS NULL)
    OR (state='RUNNING' AND step_kind IS NOT NULL AND step_subject IS NOT NULL AND step_model_group IS NOT NULL AND step_token_digest IS NOT NULL)),
 CHECK((state='RUNNING' AND lease_id IS NOT NULL AND lease_owner IS NOT NULL AND lease_token_digest IS NOT NULL AND lease_expires_at IS NOT NULL)
    OR (state<>'RUNNING' AND lease_id IS NULL AND lease_owner IS NULL AND lease_token_digest IS NULL AND lease_expires_at IS NULL)),
 FOREIGN KEY(tenant_id,profile_id,profile_digest) REFERENCES control_workflow_profile_revisions(tenant_id,profile_id,digest),
 FOREIGN KEY(tenant_id,key_version) REFERENCES control_tenant_vault_keys(tenant_id,version),
 PRIMARY KEY(tenant_id,run_id));
CREATE INDEX control_workflow_runs_queue ON control_workflow_runs(tenant_id,state,created_at,run_id);
CREATE INDEX control_workflow_runs_expiry ON control_workflow_runs(tenant_id,state,lease_expires_at);
CREATE TABLE control_workflow_admissions(
 tenant_id varchar(128) NOT NULL REFERENCES control_tenants(tenant_id),
 subscription_uid varchar(160) NOT NULL, cursor varchar(16) NOT NULL CHECK(length(cursor)=16),
 owner_id varchar(128) NOT NULL, admission_id varchar(128) NOT NULL,
 disposition varchar(16) NOT NULL CHECK(disposition IN('ADMITTED','FILTERED','REJECTED')),
 run_id varchar(128),
 CHECK((disposition='ADMITTED' AND run_id IS NOT NULL) OR (disposition<>'ADMITTED' AND run_id IS NULL)),
 FOREIGN KEY(tenant_id,run_id) REFERENCES control_workflow_runs(tenant_id,run_id),
 PRIMARY KEY(tenant_id,subscription_uid,cursor), UNIQUE(tenant_id,admission_id), UNIQUE(tenant_id,run_id));
ALTER TABLE control_workflow_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_workflow_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_workflow_runs USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_workflow_runs AS RESTRICTIVE USING(tenant_id::text=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false)) WITH CHECK(tenant_id::text=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON control_workflow_runs FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_workflow_runs TO toolgate_control_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE public.control_workflow_runs TO toolgate_control_platform;
ALTER TABLE control_workflow_admissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE control_workflow_admissions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_access ON control_workflow_admissions USING(true) WITH CHECK(true);
CREATE POLICY tenant_fence ON control_workflow_admissions AS RESTRICTIVE USING(tenant_id::text=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(false)) WITH CHECK(tenant_id::text=NULLIF(current_setting('app.tenant_id',true),'') AND public.control_tenant_access(true));
CREATE TRIGGER tenant_immutable BEFORE UPDATE OF tenant_id ON control_workflow_admissions FOR EACH ROW EXECUTE FUNCTION public.control_tenant_identity_guard();
GRANT SELECT,INSERT ON TABLE public.control_workflow_admissions TO toolgate_control_runtime;
GRANT SELECT,INSERT ON TABLE public.control_workflow_admissions TO toolgate_control_platform;

-- BEGIN TENANT CAPACITY
GRANT SELECT ON control_tenant_capacity TO toolgate_control_runtime;

GRANT SELECT,INSERT,UPDATE,DELETE ON control_tenant_capacity TO toolgate_control_platform;

CREATE FUNCTION control_capacity_seed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN INSERT INTO public.control_tenant_capacity(tenant_id) VALUES(NEW.tenant_id);RETURN NEW;END;$$;
REVOKE ALL ON FUNCTION control_capacity_seed() FROM PUBLIC;
CREATE TRIGGER control_capacity_seed AFTER INSERT ON control_tenants FOR EACH ROW EXECUTE FUNCTION control_capacity_seed();

CREATE FUNCTION control_workflow_runs_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(CASE WHEN NEW.state IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(CASE WHEN OLD.state IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_workflow_runs_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_workflow_runs FOR EACH ROW EXECUTE FUNCTION control_workflow_runs_capacity();

CREATE FUNCTION control_workflow_admissions_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.subscription_uid),0)+COALESCE(octet_length(NEW.cursor),0)+COALESCE(octet_length(NEW.owner_id),0)+COALESCE(octet_length(NEW.admission_id),0)+COALESCE(octet_length(NEW.disposition),0)+COALESCE(octet_length(NEW.run_id),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.subscription_uid),0)+COALESCE(octet_length(OLD.cursor),0)+COALESCE(octet_length(OLD.owner_id),0)+COALESCE(octet_length(OLD.admission_id),0)+COALESCE(octet_length(OLD.disposition),0)+COALESCE(octet_length(OLD.run_id),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_workflow_admissions_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_workflow_admissions FOR EACH ROW EXECUTE FUNCTION control_workflow_admissions_capacity();

CREATE FUNCTION control_workflow_profile_revisions_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_workflow_profile_revisions_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_workflow_profile_revisions FOR EACH ROW EXECUTE FUNCTION control_workflow_profile_revisions_capacity();

CREATE FUNCTION control_workflow_profile_heads_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.profile_id),0)+COALESCE(octet_length(NEW.digest),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.profile_id),0)+COALESCE(octet_length(OLD.digest),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_workflow_profile_heads_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_workflow_profile_heads FOR EACH ROW EXECUTE FUNCTION control_workflow_profile_heads_capacity();

CREATE FUNCTION control_workflow_profile_owners_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.resource_kind),0)+COALESCE(octet_length(NEW.resource_id),0)+COALESCE(octet_length(NEW.profile_id),0)+COALESCE(octet_length(NEW.definition_digest),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.resource_kind),0)+COALESCE(octet_length(OLD.resource_id),0)+COALESCE(octet_length(OLD.profile_id),0)+COALESCE(octet_length(OLD.definition_digest),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_workflow_profile_owners_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_workflow_profile_owners FOR EACH ROW EXECUTE FUNCTION control_workflow_profile_owners_capacity();

CREATE FUNCTION control_kill_gateways_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.gateway_id),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.gateway_id),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_kill_gateways_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_kill_gateways FOR EACH ROW EXECUTE FUNCTION control_kill_gateways_capacity();

CREATE FUNCTION control_kill_breakglass_requests_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.request_id),0)+COALESCE(octet_length(NEW.request_digest),0)+COALESCE(octet_length(NEW.event_id),0)+COALESCE(octet_length(NEW.certificate_sha256),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.request_id),0)+COALESCE(octet_length(OLD.request_digest),0)+COALESCE(octet_length(OLD.event_id),0)+COALESCE(octet_length(OLD.certificate_sha256),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_kill_breakglass_requests_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_kill_breakglass_requests FOR EACH ROW EXECUTE FUNCTION control_kill_breakglass_requests_capacity();

CREATE FUNCTION control_kill_proposals_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_kill_proposals_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_kill_proposals FOR EACH ROW EXECUTE FUNCTION control_kill_proposals_capacity();

CREATE FUNCTION control_kill_reviews_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.reviewer_id),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.reviewer_id),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_kill_reviews_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_kill_reviews FOR EACH ROW EXECUTE FUNCTION control_kill_reviews_capacity();

CREATE FUNCTION control_kill_closures_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.event_id),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.event_id),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_kill_closures_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_kill_closures FOR EACH ROW EXECUTE FUNCTION control_kill_closures_capacity();

CREATE FUNCTION control_invocation_kill_scopes_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.tool_id),0)+COALESCE(octet_length(NEW.package_digest),0)+COALESCE(octet_length(NEW.publisher_id),0)+COALESCE(octet_length(NEW.pool_id),0)+COALESCE(octet_length(NEW.image_digest),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.tool_id),0)+COALESCE(octet_length(OLD.package_digest),0)+COALESCE(octet_length(OLD.publisher_id),0)+COALESCE(octet_length(OLD.pool_id),0)+COALESCE(octet_length(OLD.image_digest),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_invocation_kill_scopes_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_invocation_kill_scopes FOR EACH ROW EXECUTE FUNCTION control_invocation_kill_scopes_capacity();

CREATE FUNCTION control_kill_events_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_kill_events_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_kill_events FOR EACH ROW EXECUTE FUNCTION control_kill_events_capacity();

CREATE FUNCTION control_kill_supersessions_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.kill_event_id),0)+COALESCE(octet_length(NEW.recovery_event_id),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.kill_event_id),0)+COALESCE(octet_length(OLD.recovery_event_id),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_kill_supersessions_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_kill_supersessions FOR EACH ROW EXECUTE FUNCTION control_kill_supersessions_capacity();

CREATE FUNCTION control_kill_archive_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.event_id),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.event_id),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_kill_archive_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_kill_archive FOR EACH ROW EXECUTE FUNCTION control_kill_archive_capacity();

CREATE FUNCTION control_kill_acks_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.component_id),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.component_id),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_kill_acks_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_kill_acks FOR EACH ROW EXECUTE FUNCTION control_kill_acks_capacity();

CREATE FUNCTION control_builder_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(CASE WHEN NEW.kind='TEST' AND (NEW.document::jsonb->>'state') IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(CASE WHEN OLD.kind='TEST' AND (OLD.document::jsonb->>'state') IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_builder_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_builder FOR EACH ROW EXECUTE FUNCTION control_builder_capacity();

CREATE FUNCTION control_mcp_requests_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.record),0)+COALESCE(octet_length(NEW.task),0)+COALESCE(octet_length(NEW.result),0));rows_delta:=1;queue_delta:=(CASE WHEN NEW.state IN ('RECEIVED','WAITING_FOR_POLL','SUBMITTED') THEN 1 ELSE 0 END);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.record),0)+COALESCE(octet_length(OLD.task),0)+COALESCE(octet_length(OLD.result),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(CASE WHEN OLD.state IN ('RECEIVED','WAITING_FOR_POLL','SUBMITTED') THEN 1 ELSE 0 END);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_mcp_requests_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_mcp_requests FOR EACH ROW EXECUTE FUNCTION control_mcp_requests_capacity();

CREATE FUNCTION control_enterprise_invocations_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(CASE WHEN NEW.state IN ('PENDING','PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') THEN 1 ELSE 0 END);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(CASE WHEN OLD.state IN ('PENDING','PENDING_APPROVAL','QUEUED','RESERVED','DISPATCHED','EXECUTING') THEN 1 ELSE 0 END);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_enterprise_invocations_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_enterprise_invocations FOR EACH ROW EXECUTE FUNCTION control_enterprise_invocations_capacity();

CREATE FUNCTION control_tool_host_dispatches_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(CASE WHEN NEW.state IN ('QUEUED','CLAIMED','ADMITTED') THEN 1 ELSE 0 END);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(CASE WHEN OLD.state IN ('QUEUED','CLAIMED','ADMITTED') THEN 1 ELSE 0 END);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_tool_host_dispatches_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_tool_host_dispatches FOR EACH ROW EXECUTE FUNCTION control_tool_host_dispatches_capacity();

CREATE FUNCTION control_mcp_scans_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(CASE WHEN NEW.state IN ('QUEUED','CLAIMED','ADMITTED') THEN 1 ELSE 0 END);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(CASE WHEN OLD.state IN ('QUEUED','CLAIMED','ADMITTED') THEN 1 ELSE 0 END);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_mcp_scans_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_mcp_scans FOR EACH ROW EXECUTE FUNCTION control_mcp_scans_capacity();

CREATE FUNCTION control_mcp_servers_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_mcp_servers_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_mcp_servers FOR EACH ROW EXECUTE FUNCTION control_mcp_servers_capacity();

CREATE FUNCTION control_mcp_snapshots_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_mcp_snapshots_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_mcp_snapshots FOR EACH ROW EXECUTE FUNCTION control_mcp_snapshots_capacity();

CREATE FUNCTION control_mcp_approvals_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_mcp_approvals_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_mcp_approvals FOR EACH ROW EXECUTE FUNCTION control_mcp_approvals_capacity();

CREATE FUNCTION control_server_packages_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0)+COALESCE(octet_length(NEW.content),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0)+COALESCE(octet_length(OLD.content),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_server_packages_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_server_packages FOR EACH ROW EXECUTE FUNCTION control_server_packages_capacity();

CREATE FUNCTION control_server_documents_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_server_documents_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_server_documents FOR EACH ROW EXECUTE FUNCTION control_server_documents_capacity();

CREATE FUNCTION control_secrets_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.cipher),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.cipher),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_secrets_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_secrets FOR EACH ROW EXECUTE FUNCTION control_secrets_capacity();

CREATE FUNCTION control_invocation_events_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_invocation_events_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_invocation_events FOR EACH ROW EXECUTE FUNCTION control_invocation_events_capacity();

CREATE FUNCTION control_invocation_results_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.payload),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.payload),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_invocation_results_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_invocation_results FOR EACH ROW EXECUTE FUNCTION control_invocation_results_capacity();

CREATE FUNCTION control_policy_bundles_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0)+COALESCE(octet_length(NEW.policy),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0)+COALESCE(octet_length(OLD.policy),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_policy_bundles_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_policy_bundles FOR EACH ROW EXECUTE FUNCTION control_policy_bundles_capacity();

CREATE FUNCTION control_triggers_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_triggers_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_triggers FOR EACH ROW EXECUTE FUNCTION control_triggers_capacity();

CREATE FUNCTION control_audit_anchors_capacity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE owner text;bytes_delta bigint:=0;rows_delta bigint:=0;queue_delta bigint:=0;
BEGIN
 IF TG_OP<>'DELETE' THEN owner:=NEW.tenant_id;bytes_delta:=(COALESCE(octet_length(NEW.document),0)+COALESCE(octet_length(NEW.signature),0));rows_delta:=1;queue_delta:=(0);END IF;
 IF TG_OP<>'INSERT' THEN owner:=OLD.tenant_id;bytes_delta:=bytes_delta-(COALESCE(octet_length(OLD.document),0)+COALESCE(octet_length(OLD.signature),0));rows_delta:=rows_delta-1;queue_delta:=queue_delta-(0);END IF;
 UPDATE public.control_tenant_capacity SET stored_bytes=stored_bytes+bytes_delta,stored_rows=stored_rows+rows_delta,queued_rows=queued_rows+queue_delta WHERE tenant_id=owner;
 IF NOT FOUND THEN RAISE EXCEPTION 'tenant capacity missing';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END;$$;
REVOKE ALL ON FUNCTION control_audit_anchors_capacity() FROM PUBLIC;
CREATE TRIGGER capacity_account AFTER INSERT OR UPDATE OR DELETE ON control_audit_anchors FOR EACH ROW EXECUTE FUNCTION control_audit_anchors_capacity();
-- END TENANT CAPACITY
