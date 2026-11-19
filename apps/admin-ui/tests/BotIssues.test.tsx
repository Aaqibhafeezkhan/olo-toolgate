// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,render,screen,waitFor,within} from '@testing-library/react';
import {ApiError,ControlClient} from '../src/api';
import {BotIssues} from '../src/BotIssues';
import type {WorkflowProfileStatus} from '@olo-labs/toolgate-contracts/v2';

afterEach(()=>{cleanup();vi.restoreAllMocks();});
const status:WorkflowProfileStatus={profileId:'support',profileDigest:'sha256:'+'a'.repeat(64),state:'REVIEWED',generation:0,dependenciesReady:false};
function rig(){
 const client=new ControlClient('private-token',vi.fn());
 const diagnostics=vi.spyOn(client,'workflowProfileDiagnostics').mockResolvedValue({profileId:'support',profileDigest:status.profileDigest,blockers:['Model intake: missing vault secret model-key.','Package reader: waiting for an approved device.']});
 const current=vi.spyOn(client,'workflowProfileStatus').mockResolvedValue(status);const onStatus=vi.fn();
 const view=(refreshKey=0)=><BotIssues client={client} profileId="support" status={status} dirty={false} busy={false} refreshKey={refreshKey} onStatus={onStatus}/>;
 return {client,diagnostics,current,onStatus,view};
}
it('keeps every blocker in a red bullet panel until a successful check clears it',async()=>{
 const r=rig();const page=render(r.view());
 const missing=await screen.findByText('Model intake: missing vault secret model-key.');
 const panel=screen.getByRole('region',{name:'Bot issues'});expect(panel.className).toContain('notice error');
 expect(within(panel).getAllByRole('listitem')).toHaveLength(2);expect(missing.closest('li')).not.toBeNull();
 expect(r.onStatus).toHaveBeenCalledWith(status);
 r.diagnostics.mockResolvedValue({profileId:'support',profileDigest:status.profileDigest,blockers:[]});r.current.mockResolvedValue({...status,dependenciesReady:true});page.rerender(r.view(1));
 await waitFor(()=>expect(screen.queryByRole('region',{name:'Bot issues'})).toBeNull());
 expect(r.onStatus).toHaveBeenLastCalledWith({...status,dependenciesReady:true});
});
it('retains previous blockers on a failed refresh and never displays the raw exception',async()=>{
 const r=rig();const page=render(r.view());await screen.findByText('Model intake: missing vault secret model-key.');
 r.diagnostics.mockRejectedValue(new Error('PRIVATE upstream body and token'));page.rerender(r.view(1));
 await screen.findByText(/Unable to check this bot/);expect(screen.getByText('Model intake: missing vault secret model-key.')).toBeTruthy();
 expect(screen.queryByText(/PRIVATE/)).toBeNull();expect(within(screen.getByRole('region',{name:'Bot issues'})).getAllByRole('listitem')).toHaveLength(3);
});
it('shows draft/action errors as bullets even before an imported revision is stored',()=>{
 const r=rig();render(<BotIssues client={r.client} profileId="support" dirty busy={false} refreshKey={0} onStatus={r.onStatus} actionError={new ApiError(403,'FORBIDDEN')}/>);
 const panel=screen.getByRole('region',{name:'Bot issues'});expect(within(panel).getAllByRole('listitem')).toHaveLength(2);
 expect(screen.getByText('Stage the edited bot revision before review and publication.')).toBeTruthy();expect(r.diagnostics).not.toHaveBeenCalled();
});
it('does not let a late response replace the newly selected revision diagnostics',async()=>{
 const r=rig();let resolve!:(value:Awaited<ReturnType<ControlClient['workflowProfileDiagnostics']>>)=>void;
 r.diagnostics.mockImplementationOnce(()=>new Promise(done=>{resolve=done;}));const page=render(r.view());
 const next={...status,profileDigest:'sha256:'+'b'.repeat(64)};r.diagnostics.mockResolvedValue({...next,blockers:['New revision requires graph preparation.']});r.current.mockResolvedValue(next);
 page.rerender(<BotIssues client={r.client} profileId="support" status={next} dirty={false} busy={false} refreshKey={0} onStatus={r.onStatus}/>);
 await screen.findByText('New revision requires graph preparation.');resolve({...status,blockers:['Obsolete revision error.']});
 await waitFor(()=>expect(screen.queryByText('Obsolete revision error.')).toBeNull());expect(screen.getByText('New revision requires graph preparation.')).toBeTruthy();
});
