// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
export type LoginLink={username:string;password:string};

/** Prefill external login links once; credentials are never saved in browser storage. */
export function readLoginLink():LoginLink|undefined {
  const {hash,search}=window.location;
  if(hash.length+search.length>4096)return;
  const query=new URLSearchParams(search),fragment=new URLSearchParams(hash.startsWith('#login?')?hash.slice(7):'');
  const username=fragment.get('username')??query.get('username');
  const password=fragment.get('password')??query.get('password')??'';
  if(!username||!/^[a-zA-Z0-9][a-zA-Z0-9._@-]{0,127}$/.test(username)||password.length>256||/[\x00-\x1f\x7f]/.test(password))return;
  return {username,password};
}
/** Strip credential parameters before the console starts any network requests. */
export function clearLoginLink():void {
  const url=new URL(window.location.href);let changed=false;
  if(url.hash.startsWith('#login?')){url.hash='';changed=true;}
  for(const name of ['username','password'])if(url.searchParams.has(name)){url.searchParams.delete(name);changed=true;}
  if(changed)window.history.replaceState(null,'',url.pathname+url.search+url.hash);
}
