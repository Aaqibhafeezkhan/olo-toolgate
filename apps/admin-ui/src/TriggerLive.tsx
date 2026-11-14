// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect,useState} from 'react';
import type {TriggerStreamServerMessage,TriggerViewEvent} from '@olo-labs/toolgate-contracts/v2';
import type {ControlClient} from './api';

const PROTOCOL='toolgate.triggers.v1';
const MAX_EVENTS=100;

export function TriggerLive({client,assignment,close}:{client:ControlClient;assignment:string;close:()=>void}){
  const [events,setEvents]=useState<TriggerViewEvent[]>([]);
  const [status,setStatus]=useState('Connecting…');
  useEffect(()=>{
    const abort=new AbortController();
    let socket:WebSocket|undefined;
    let terminal=false;
    function stop(message:string,clear=false){
      terminal=true;
      setStatus(message);
      if(clear)setEvents([]);
      socket?.close();
    }
    async function connect(){
      if(location.protocol!=='https:'){
        stop('Live triggers require HTTPS.');
        return;
      }
      try{
        const {ticket}=await client.triggerStreamTicket(abort.signal);
        if(abort.signal.aborted)return;
        socket=new WebSocket(`wss://${location.host}/api/control/v1/triggers/stream`,[PROTOCOL,`ticket.${ticket}`]);
        socket.onopen=()=>{
          if(abort.signal.aborted)return;
          if(socket?.protocol!==PROTOCOL){stop('The trigger stream protocol was not accepted.');return;}
          socket.send(JSON.stringify({type:'watch',sources:{assignments:[assignment]}}));
        };
        socket.onmessage=({data})=>{
          if(abort.signal.aborted||terminal)return;
          try{
            const message=JSON.parse(String(data)) as TriggerStreamServerMessage;
            switch(message.type){
              case 'subscribed':setStatus('Watching');break;
              case 'event':{
                const event=message.event;
                if(!event)throw new Error('Missing event');
                setEvents(current=>current.some(item=>item.id===event.id&&item.source===event.source)
                  ?current:[...current,event].slice(-MAX_EVENTS));
                socket?.send(JSON.stringify({type:'credit',n:1}));
                break;
              }
              case 'revoked':stop('Access to this trigger stream was revoked.',true);break;
              case 'error':stop('Control refused the trigger watch.',true);break;
              case 'gap':setStatus('Some events expired before they could be shown.');break;
            }
          }catch{stop('Control returned an invalid trigger stream message.',true);}
        };
        socket.onerror=()=>{if(!abort.signal.aborted&&!terminal)stop('Could not connect to the trigger stream.');};
        socket.onclose=()=>{if(!abort.signal.aborted&&!terminal)setStatus('Trigger stream disconnected.');};
      }catch{if(!abort.signal.aborted)stop('Could not open the trigger watch.');}
    }
    void connect();
    return()=>{abort.abort();socket?.close();};
  },[client,assignment]);

  return <section>
    <div className="page-heading"><h3>Live events for {assignment}</h3><button onClick={close}>Stop watching</button></div>
    <p role="status">{status}</p>
    {events.length>0&&<ol>{events.map(event=><li key={JSON.stringify([event.source,event.id])}>
      <details><summary>{event.type} · {event.source}</summary><pre>{JSON.stringify(event,null,2)}</pre></details>
    </li>)}</ol>}
  </section>;
}
