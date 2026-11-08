// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import type {EffectClassification, EffectIntentBlueprint, RouteDeclaration, RouteEffectPolicy, FencedTarget, FencedTargetClass} from '@olo-labs/toolgate-contracts/v2';

export type EffectDraft = {classification: EffectClassification | ''; consent: boolean; risk: boolean; intent: string; fencedTarget?: FencedTarget;
  /** ADR 060: reviewer declares the target commits before a synchronous success response. */
  authoritative?: boolean};
export type EffectDrafts = Record<string, EffectDraft>;
export function draft(): EffectDraft { return {classification: '', consent: false, risk: false, intent: ''}; }
export function reviewedEffects(routes: readonly RouteDeclaration[], drafts: EffectDrafts): RouteEffectPolicy[] {
  return routes.map(route => {
    const value = drafts[route.id] ?? draft();
    if (!value.classification) throw new Error('Review the effect classification for ' + route.id + '.');
    const critical = value.classification === 'DESTRUCTIVE' || value.classification === 'FINANCIAL';
    if (critical && !value.fencedTarget && !value.risk) throw new Error('Acknowledge the unfenced target risk for ' + route.id + '.');
    if (value.fencedTarget && (value.classification === 'READ' || !/^[a-z][a-z0-9-]{0,63}$/.test(value.fencedTarget.targetId)))
      throw new Error('Choose a valid fenced target identity for ' + route.id + '.');
    let intent: EffectIntentBlueprint;
    try {
      const parsed: unknown = JSON.parse(value.intent);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error();
      intent = parsed as EffectIntentBlueprint;
    } catch { throw new Error('Enter a request intent JSON object for ' + route.id + '.'); }
    return {routeId: route.id, classification: value.classification, consentRequired: value.consent,
      acknowledgedUnfencedRisk: critical && !value.fencedTarget && value.risk, intent,
      ...(value.fencedTarget ? {fencedTarget: value.fencedTarget} : {}),
      ...(value.authoritative && value.classification !== 'READ' ? {authoritativeResponse: true} : {})};
  });
}

export function EffectPolicyReview({routes, values, disabled, onChange}: {
  routes: readonly RouteDeclaration[]; values: EffectDrafts; disabled: boolean; onChange: (values: EffectDrafts) => void;
}) {
  function change(id: string, update: Partial<EffectDraft>) {
    onChange({...values, [id]: {...(values[id] ?? draft()), ...update}});
  }
  if (!routes.length) return null;
  return <section aria-label="Route effect review"><h3>Review route effects</h3>
    <p>Review the recipient and full payload for every route. Control checks each call against this intent and the original arguments.
      Fenced targets consume grants at Control before acting. An HTTP reply alone does not prove business completion.</p>
    {routes.map(route => {
      const value = values[route.id] ?? draft();
      const critical = value.classification === 'DESTRUCTIVE' || value.classification === 'FINANCIAL';
      return <fieldset key={route.id} disabled={disabled}><legend>{route.id}: {route.method} {route.path} → {route.destination}</legend>
        <label htmlFor={'effect-class-' + route.id}>Effect classification for {route.id}</label>
        <select id={'effect-class-' + route.id} required value={value.classification}
          onChange={e => change(route.id, {classification: e.target.value as EffectClassification | '', risk: false, fencedTarget: undefined})}>
          <option value="">Choose a classification</option>{(['READ', 'WRITE', 'DESTRUCTIVE', 'FINANCIAL'] as const)
            .map(kind => <option key={kind} value={kind}>{kind}</option>)}</select>
        <label htmlFor={'effect-target-class-' + route.id}>Target fencing for {route.id}</label>
        <select id={'effect-target-class-' + route.id} value={value.fencedTarget?.targetClass ?? ''}
          disabled={value.classification === 'READ'}
          onChange={e => change(route.id, {risk: false, fencedTarget: e.target.value
            ? {targetId: value.fencedTarget?.targetId ?? '', targetClass: e.target.value as FencedTargetClass} : undefined})}>
          <option value="">Unfenced target</option>
          <option value="SHARED_SQL">Shared SQL transaction</option>
          <option value="SQL_WITH_EXTERNAL_ACTION">SQL ownership with external action</option>
        </select>
        {value.fencedTarget && <>
          <label htmlFor={'effect-target-id-' + route.id}>Fenced target identity for {route.id}</label>
          <input id={'effect-target-id-' + route.id} required maxLength={64} pattern="[a-z][a-z0-9-]{0,63}"
            value={value.fencedTarget.targetId} onChange={e => change(route.id,
              {fencedTarget: {...value.fencedTarget!, targetId: e.target.value}})} />
          <p>All replicas must use the same durable claim store and a dedicated target credential.
            External actions remain uncertain after an interrupted apply; the declaration alone supplies no guarantee.</p>
        </>}
        <label htmlFor={'effect-intent-' + route.id}>Request intent for {route.id} (JSON)</label>
        <textarea id={'effect-intent-' + route.id} required maxLength={65536} value={value.intent}
          onChange={e => change(route.id, {intent: e.target.value})} />
        <p>Supply path, query, headers, contentType, bodyMode and body. Constants and closed $arg JSON-pointer references bind the request;
          unsupported dynamic values are refused. Use CANONICAL_JSON for a JSON body, or NONE with an empty contentType and null body.</p>
        <label><input type="checkbox" checked={value.consent} onChange={e => change(route.id, {consent: e.target.checked})} />
          Require exact effect consent for {route.id}</label>
        {value.consent && <p>Calls on this route are blocked until exact effect consent is available.</p>}
        {value.classification && value.classification !== 'READ' && <label><input type="checkbox" checked={Boolean(value.authoritative)}
          onChange={e => change(route.id, {authoritative: e.target.checked})} />
          {route.id} commits the effect before it returns a success response</label>}
        {value.authoritative && value.classification !== 'READ' && <p>Broker-seen responses on this route are shown as broker-observed evidence, which depends on an intact host.
          They do not resolve the outcome; target confirmation or review is still required.</p>}
        {critical && !value.fencedTarget && <label><input type="checkbox" required checked={value.risk}
          onChange={e => change(route.id, {risk: e.target.checked})} />
          I acknowledge that {route.id} has an unfenced {value.classification.toLowerCase()} effect and may need reconciliation.</label>}
      </fieldset>;
    })}
  </section>;
}
