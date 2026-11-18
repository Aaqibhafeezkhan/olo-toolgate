// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen} from '@testing-library/react';
import type {DeploymentProfile,WorkflowProfileStatus,WorkflowRunProgressEvent} from '@olo-labs/toolgate-contracts/v2';
import {ApiError,ControlClient} from '../src/api';
import {BotRunDialog} from '../src/BotRunDialog';
import fixture from '../../../tests/fixtures/contracts/v2/valid.json';

afterEach(()=>{cleanup();vi.restoreAllMocks();});
const profile={profileVersion:2,profileId:'support',packages:[],workflows:fixture.WorkflowConfiguration} as DeploymentProfile;
const workflow=profile.workflows!.workflows[0];
const status={profileId:'support',profileDigest:'sha256:'+'a'.repeat(64),generation:1,state:'PUBLISHED',dependenciesReady:true} as WorkflowProfileStatus;
const accepted={runId:'run-1',profileId:'support',profileDigest:status.profileDigest,workflowId:workflow.id,state:'QUEUED' as const};
const view=(state='RUNNING')=>({runId:'run-1',workflowId:workflow.id,workflowVersionId:workflow.versionId,profileDigest:status.profileDigest,state,message:'private input',outcome:state==='SUCCEEDED'?{result:{summary:'Result from the bot'},code:null}:null});
function event(sequence:number,kind:string):WorkflowRunProgressEvent{return {sequence,kind,occurredAt:'2026-11-18T00:00:00Z',source:kind==='QUEUED'||kind==='SUCCEEDED'?'CONTROL':'RUNTIME'};}
function choose(){fireEvent.change(screen.getByLabelText('Bot input'),{target:{value:'Summarize the incident'}});fireEvent.click(screen.getByRole('button',{name:'Run bot'}));}

it('submits text once and renders recorded progress and the terminal result',async()=>{
 const client=new ControlClient('token',vi.fn());let finished=false;
 const start=vi.spyOn(client,'startWorkflowRun').mockResolvedValue(accepted);
 vi.spyOn(client,'workflowRun').mockImplementation(async()=>view(finished?'SUCCEEDED':'RUNNING'));
 vi.spyOn(client,'workflowRunProgress').mockImplementation(async(_id,after)=>({items:after===0?[event(1,'QUEUED'),event(2,'MODEL_STARTED')]:finished?[event(3,'MODEL_COMPLETED'),event(4,'SUCCEEDED')]:[],nextCursor:finished&&after>0?4:2}));
 render(<BotRunDialog client={client} profile={profile} status={status} workflow={workflow} close={vi.fn()}/>);
 choose();await screen.findByText('Calling model');expect(start).toHaveBeenCalledOnce();
 expect(start.mock.calls[0].slice(0,3)).toEqual(['support',workflow.id,{profileDigest:status.profileDigest,expectedGeneration:1,entryAgentId:workflow.agents.find(a=>a.entryEligible)!.agentId,message:'Summarize the incident'}]);
 finished=true;await screen.findByText(/Result from the bot/,{},{timeout:4000});
 expect(screen.getByText('Model returned')).toBeTruthy();expect(screen.getByLabelText('Bot input').matches(':disabled')).toBe(true);expect(start).toHaveBeenCalledOnce();
});

it('recovers an unknown submission with exactly the same key and input',async()=>{
 const client=new ControlClient('token',vi.fn());
 const start=vi.spyOn(client,'startWorkflowRun').mockRejectedValueOnce(new ApiError(0,'NETWORK')).mockResolvedValue(accepted);
 vi.spyOn(client,'workflowRun').mockResolvedValue(view());vi.spyOn(client,'workflowRunProgress').mockResolvedValue({items:[],nextCursor:0});
 render(<BotRunDialog client={client} profile={profile} status={status} workflow={workflow} close={vi.fn()}/>);
 choose();await screen.findByText(/Submission status is unknown/);expect(screen.getByLabelText('Bot input').matches(':disabled')).toBe(true);
 fireEvent.click(screen.getByRole('button',{name:'Check same submission'}));await screen.findByText('run-1');
 expect(start.mock.calls[1]).toEqual(start.mock.calls[0]);expect(start).toHaveBeenCalledTimes(2);
});

it('resumes failed polling without submitting another run',async()=>{
 const client=new ControlClient('token',vi.fn());const start=vi.spyOn(client,'startWorkflowRun').mockResolvedValue(accepted);
 vi.spyOn(client,'workflowRun').mockRejectedValueOnce(new ApiError(503,'UNAVAILABLE')).mockResolvedValue(view('SUCCEEDED'));
 vi.spyOn(client,'workflowRunProgress').mockResolvedValue({items:[event(1,'SUCCEEDED')],nextCursor:1});
 render(<BotRunDialog client={client} profile={profile} status={status} workflow={workflow} close={vi.fn()}/>);
 choose();fireEvent.click(await screen.findByRole('button',{name:'Resume progress'}));
 await screen.findByText(/Result from the bot/);expect(start).toHaveBeenCalledOnce();
});

it('keeps draft revisions non-runnable',async()=>{
 const client=new ControlClient('token',vi.fn());const start=vi.spyOn(client,'startWorkflowRun');
 render(<BotRunDialog client={client} profile={profile} status={{...status,state:'DRAFT'}} workflow={workflow} close={vi.fn()}/>);
 expect(screen.getByText('Publish this bot revision before running it.')).toBeTruthy();expect(screen.getByRole('button',{name:'Run bot'}).matches(':disabled')).toBe(true);expect(start).not.toHaveBeenCalled();
});
