// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import type {DeploymentProfile,WorkflowDefinition} from '@olo-labs/toolgate-contracts/v2';

/** Inspect only installed JSON graph tool nodes; arbitrary argument/configuration text is not a reference. */
export function graphToolIds(workflow:WorkflowDefinition):string[]{
  if(workflow.graph.packageId!=='olo.json-graph')return [];
  const nodes=workflow.graph.config.nodes;
  if(!Array.isArray(nodes))throw Error('JSON graph nodes are missing.');
  return nodes.filter(node=>node&&typeof node==='object'&&!Array.isArray(node)&&node.kind==='tool').map(node=>{
    const ref=(node as Record<string,unknown>).toolRef;
    if(typeof ref!=='string'||ref.split('/').length!==2||ref.split('/').some(part=>!part))throw Error('JSON graph tool reference is invalid.');
    return ref;
  });
}
export function configuredToolIds(profile:DeploymentProfile):string[]{
  return [...new Set([
    ...(profile.workflows?.workflows.flatMap(workflow=>[...(workflow.contextSources??[]).map(item=>item.toolId),...graphToolIds(workflow)])??[]),
    ...(profile.workflows?.agentDefinitions.flatMap(agent=>agent.contextSources.map(item=>item.toolId))??[])
  ])];
}
