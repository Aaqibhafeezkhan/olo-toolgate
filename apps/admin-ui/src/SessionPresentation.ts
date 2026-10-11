// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
/** Display/preferences only, after server authentication; never used as authorization. */
export function sessionPresentation(token:string){
  try{
    const part=token.split('.')[1],claims=JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(part.replaceAll('-','+').replaceAll('_','/')),c=>c.charCodeAt(0)))) as Record<string,unknown>;
    const text=(key:string)=>typeof claims[key]==='string'?String(claims[key]).slice(0,512):undefined;
    return {name:text('name')??text('preferred_username')??text('user_id')??text('sub')??'Signed-in user',userId:text('user_id'),storageKey:`toolgate.navigation.${JSON.stringify([text('iss'),text('tenant_id'),text('sub')])}`};
  }catch{return {name:'Signed-in user',userId:undefined,storageKey:'toolgate.navigation.local'};}
}
