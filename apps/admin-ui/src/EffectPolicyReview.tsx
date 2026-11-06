// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import type {EffectClassification, EffectIntentBlueprint, RouteDeclaration, RouteEffectPolicy} from '@olo-labs/toolgate-contracts/v2';

export type EffectDraft = {classification: EffectClassification | ''; consent: boolean; risk: boolean; intent: string};
export type EffectDrafts = Record<string, EffectDraft>;
export function draft(): EffectDraft { return {classification: '', consent: false, risk: false, intent: ''}; }
export function reviewedEffects(routes: readonly RouteDeclaration[], drafts: EffectDrafts): RouteEffectPolicy[] {
  return routes.map(route => {
    const value = drafts[route.id] ?? draft();
    if (!value.classification) throw new Error('Review the effect classification for ' + route.id + '.');
    const critical = value.classification === 'DESTRUCTIVE' || value.classification === 'FINANCIAL';
    if (critical && !value.risk) throw new Error('Acknowledge the unfenced target risk for ' + route.id + '.');
    let intent: EffectIntentBlueprint;
    try {
      const parsed: unknown = JSON.parse(value.intent);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error();
      intent = parsed as EffectIntentBlueprint;
    } catch { throw new Error('Enter a request intent JSON object for ' + route.id + '.'); }
    return {routeId: route.id, classification: value.classification, consentRequired: value.consent,
      acknowledgedUnfencedRisk: critical && value.risk, intent};
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
      These targets do not consume ToolGate grants; a send can remain uncertain even after a successful HTTP reply.</p>
    {routes.map(route => {
      const value = values[route.id] ?? draft();
      const critical = value.classification === 'DESTRUCTIVE' || value.classification === 'FINANCIAL';
      return <fieldset key={route.id} disabled={disabled}><legend>{route.id}: {route.method} {route.path} → {route.destination}</legend>
        <label htmlFor={'effect-class-' + route.id}>Effect classification for {route.id}</label>
        <select id={'effect-class-' + route.id} required value={value.classification}
          onChange={e => change(route.id, {classification: e.target.value as EffectClassification | '', risk: false})}>
          <option value="">Choose a classification</option>{(['READ', 'WRITE', 'DESTRUCTIVE', 'FINANCIAL'] as const)
            .map(kind => <option key={kind} value={kind}>{kind}</option>)}</select>
        <label htmlFor={'effect-intent-' + route.id}>Request intent for {route.id} (JSON)</label>
        <textarea id={'effect-intent-' + route.id} required maxLength={65536} value={value.intent}
          onChange={e => change(route.id, {intent: e.target.value})} />
        <p>Supply path, query, headers, contentType, bodyMode and body. Constants and closed $arg JSON-pointer references bind the request;
          unsupported dynamic values are refused. Use CANONICAL_JSON for a JSON body, or NONE with an empty contentType and null body.</p>
        <label><input type="checkbox" checked={value.consent} onChange={e => change(route.id, {consent: e.target.checked})} />
          Require exact effect consent for {route.id}</label>
        {value.consent && <p>Calls on this route are blocked until exact effect consent is available.</p>}
        {critical && <label><input type="checkbox" required checked={value.risk}
          onChange={e => change(route.id, {risk: e.target.checked})} />
          I acknowledge that {route.id} has an unfenced {value.classification.toLowerCase()} effect and may need reconciliation.</label>}
      </fieldset>;
    })}
  </section>;
}
