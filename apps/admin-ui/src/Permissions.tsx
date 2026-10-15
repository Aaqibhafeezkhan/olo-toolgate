// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useSyncExternalStore,type ReactNode} from 'react';
import {ControlClient} from './api';
import {managementCatalog} from './managementPermissions.generated';

const requirements=managementCatalog.routes as Readonly<Record<string,readonly string[]>>;
const safeActions=new Set(['read','run']);

/** Presentation consumes server decisions. It never resolves roles, owners or group membership. */
const noSubscribe=()=>()=>{};
const emptySnapshot=()=>0;
export function usePermissions(client?:ControlClient){
 useSyncExternalStore(client?.subscribePermissions??noSubscribe,client?.permissionSnapshot??emptySnapshot,client?.permissionSnapshot??emptySnapshot);
 const can=(permission:string)=>client?.can(permission)??false;
 const view=(route:string)=>route==='about'||route==='overview'||(requirements[route]??[]).some(can);
 const resource=(route:string)=>requirements[route]?.[0]?.split('.')[0];
 const edit=(route:string)=>{const feature=resource(route);return Boolean(feature&&managementCatalog.resources.some(r=>r.id===feature&&r.actions.some(action=>!safeActions.has(action)&&can(`${feature}.${action}`))));};
 return {can,view,resource,edit};
}

/** Shared read-only presentation for feature screens; directory dialogs guard individual actions. */
export function FeatureAccess({client,route,children}:{client:ControlClient;route:string;children:ReactNode}){
 const permissions=usePermissions(client);
 if(!permissions.view(route))return <section><h1>Access unavailable</h1><p>Your role does not grant access to this feature.</p></section>;
 const directory=(managementCatalog.directoryRoutes as readonly string[]).includes(route);
 if(directory||permissions.edit(route)||route==='about'||route==='overview')return <>{children}</>;
 return <><p role="status">View only. Your role does not grant changes to this feature.</p><fieldset disabled className="readonly-feature">{children}</fieldset></>;
}
