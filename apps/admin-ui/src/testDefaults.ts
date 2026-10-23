// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

/** Test input built from the typed defaults in a verified tool definition (docs/contracts/platform-requirements.md).
 *
 * JSON Schema `default` is an annotation; this resolver constructs the test payload explicitly and never changes
 * invocation semantics elsewhere. A default is used only when it validates against its own property schema. Required
 * properties without a valid default stay missing, so the person supplies them: paths, identities and credentials are
 * never invented. Unsupported schema constructs (references, compositions) also stay missing. */
export type TestInput = { readonly value: Record<string, unknown>; readonly missing: readonly string[] };

type Schema = Record<string, unknown>;
const isObject = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const UNSUPPORTED = ['$ref', 'oneOf', 'anyOf', 'allOf', 'not', 'if'];

/** Whether a value satisfies the subset of JSON Schema the SDK emits for parameters. */
export function conforms(schema: Schema, value: unknown): boolean {
  if (UNSUPPORTED.some(key => key in schema)) return false;
  if (Array.isArray(schema.enum) && !schema.enum.some(option => JSON.stringify(option) === JSON.stringify(value))) return false;
  if ('const' in schema && JSON.stringify(schema.const) !== JSON.stringify(value)) return false;
  switch (schema.type) {
    case 'string':
      if (typeof value !== 'string') return false;
      if (typeof schema.maxLength === 'number' && [...value].length > schema.maxLength) return false;
      if (typeof schema.minLength === 'number' && [...value].length < schema.minLength) return false;
      if (typeof schema.pattern === 'string') { try { if (!new RegExp(schema.pattern, 'u').test(value)) return false; } catch { return false; } }
      return true;
    case 'integer': if (!Number.isInteger(value)) return false; return withinBounds(schema, value as number);
    case 'number': if (typeof value !== 'number' || !Number.isFinite(value)) return false; return withinBounds(schema, value);
    case 'boolean': return typeof value === 'boolean';
    case 'array': {
      if (!Array.isArray(value)) return false;
      if (typeof schema.maxItems === 'number' && value.length > schema.maxItems) return false;
      if (typeof schema.minItems === 'number' && value.length < schema.minItems) return false;
      return !isObject(schema.items) || value.every(item => conforms(schema.items as Schema, item));
    }
    case 'object': {
      if (!isObject(value)) return false;
      const properties = isObject(schema.properties) ? schema.properties : {};
      const required = Array.isArray(schema.required) ? schema.required.filter((n): n is string => typeof n === 'string') : [];
      if (required.some(name => !(name in value))) return false;
      if (schema.additionalProperties === false && Object.keys(value).some(name => !(name in properties))) return false;
      return Object.entries(value).every(([name, item]) => !isObject(properties[name]) || conforms(properties[name] as Schema, item));
    }
    default: return false;
  }
}
function withinBounds(schema: Schema, value: number): boolean {
  if (typeof schema.minimum === 'number' && value < schema.minimum) return false;
  if (typeof schema.maximum === 'number' && value > schema.maximum) return false;
  return true;
}

/** Resolves the test payload for an object input schema. Nested objects without their own default are resolved from
 *  their properties when that yields a valid value. */
export function testDefaults(schema: unknown, path = ''): TestInput {
  if (!isObject(schema) || schema.type !== 'object') return { value: {}, missing: path ? [path] : ['(input schema)'] };
  const properties = isObject(schema.properties) ? schema.properties : {};
  const required = new Set(Array.isArray(schema.required) ? schema.required.filter((n): n is string => typeof n === 'string') : []);
  const value: Record<string, unknown> = {}; const missing: string[] = [];
  for (const [name, property] of Object.entries(properties)) {
    const at = path ? `${path}.${name}` : name;
    if (!isObject(property)) { if (required.has(name)) missing.push(at); continue; }
    if ('default' in property && conforms(property, property.default)) { value[name] = structuredClone(property.default); continue; }
    if (property.type === 'object' && !UNSUPPORTED.some(key => key in property)) {
      const nested = testDefaults(property, at);
      if (!nested.missing.length && (required.has(name) || Object.keys(nested.value).length) && conforms(property, nested.value)) { value[name] = nested.value; continue; }
      if (required.has(name)) missing.push(...(nested.missing.length ? nested.missing : [at]));
      continue;
    }
    if (required.has(name)) missing.push(at);
  }
  return { value, missing };
}
