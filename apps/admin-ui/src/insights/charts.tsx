// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useLayoutEffect,useRef,useState,type ReactNode} from 'react';
import type {InsightsGranularity} from '@olo-labs/toolgate-contracts/v2';
import {GROUPS,duration,percentile,time,type Bucket} from './model';

const HEIGHT=220,PAD={top:12,right:56,bottom:28,left:52};

function useWidth(){
  const ref=useRef<HTMLDivElement>(null),[width,setWidth]=useState(640);
  useLayoutEffect(()=>{
    const node=ref.current;if(!node)return;
    const measure=()=>setWidth(Math.max(280,Math.floor(node.getBoundingClientRect().width)));
    measure();
    if(typeof ResizeObserver==='undefined')return;
    const observer=new ResizeObserver(measure);observer.observe(node);return()=>observer.disconnect();
  },[]);
  return [ref,width] as const;
}
/** Round axis maxima to 1, 2 or 5 times a power of ten so ticks read cleanly. */
export function niceMax(value:number):number{
  if(value<=0)return 1;
  const power=10**Math.floor(Math.log10(value));
  return [1,2,5,10].map(f=>f*power).find(v=>v>=value)!;
}
/** Evenly spaced ticks on round values: fifths for a maximum of 5×10ⁿ, quarters for 1 or 2×10ⁿ. */
export function ticks(max:number):number[]{
  const lead=Math.round(max/10**Math.floor(Math.log10(max))),n=lead===5?5:4;
  return Array.from({length:n+1},(_,i)=>(max*i)/n);
}
function compact(v:number):string{return v>=1_000_000?`${+(v/1_000_000).toFixed(1)}M`:v>=1_000?`${+(v/1_000).toFixed(1)}k`:`${+v.toFixed(v<10&&v%1?1:0)}`;}

/** A bar whose data end (top) is rounded and whose base sits square on the baseline or on the segment below. */
function bar(x:number,y:number,w:number,h:number,round:boolean):string{
  if(!round||h<1)return `M${x},${y}h${w}v${h}h${-w}z`;
  const r=Math.min(4,w/2,h);
  return `M${x},${y+h}V${y+r}Q${x},${y} ${x+r},${y}H${x+w-r}Q${x+w},${y} ${x+w},${y+r}V${y+h}Z`;
}

function Frame({title,description,legend,table,children,note}:{title:string;description:string;legend:ReactNode;table:ReactNode;children:(width:number)=>ReactNode;note?:ReactNode}){
  const [ref,width]=useWidth(),[showTable,setShowTable]=useState(false);
  return <figure className="insights-chart" aria-label={title}>
    <figcaption><h3>{title}</h3><button type="button" className="link-button" aria-pressed={showTable} onClick={()=>setShowTable(v=>!v)}>{showTable?'Show chart':'Show table'}</button></figcaption>
    <p className="insights-chart-description">{description}</p>
    <div className="insights-legend">{legend}</div>
    {note}
    <div ref={ref} className="insights-plot">{showTable?table:children(width)}</div>
  </figure>;
}
function Tooltip({x,width,children}:{x:number;width:number;children:ReactNode}){
  const left=Math.min(Math.max(8,x+12),width-200);
  return <div className="insights-tooltip" role="status" style={{left}}>{children}</div>;
}

/** Calls per bucket, stacked by outcome group. Outcome colors are status colors: each always carries its label. */
export function OutcomeChart({data,granularity,unavailableBefore}:{data:Bucket[];granularity:InsightsGranularity;unavailableBefore?:number|null}){
  const [hover,setHover]=useState<number>();
  const max=niceMax(Math.max(0,...data.map(b=>b.total)));
  const legend=GROUPS.map(g=><span key={g.id} className="insights-legend-item"><i className={`swatch outcome-${g.id}`} aria-hidden="true"/>{g.label}</span>);
  const table=<div className="table-scroll"><table className="insights-table"><caption className="visually-hidden">Calls per {granularity.toLowerCase()} by outcome</caption>
    <thead><tr><th scope="col">Bucket (UTC)</th>{GROUPS.map(g=><th key={g.id} scope="col">{g.label}</th>)}<th scope="col">Total</th></tr></thead>
    <tbody>{data.map(b=><tr key={b.start}><th scope="row">{time(b.start,granularity)}</th>{GROUPS.map(g=><td key={g.id}>{b.counts[g.id]}</td>)}<td>{b.total}</td></tr>)}</tbody></table></div>;
  const expired=unavailableBefore!=null&&data.some(b=>b.start<unavailableBefore)?<p className="insights-note" role="note">
    Data before {time(unavailableBefore,granularity)} UTC has expired at this granularity. Those buckets are not shown as zero.</p>:null;
  return <Frame title="Calls by outcome" description={`Logical calls per ${granularity.toLowerCase()} (UTC), by the outcome each has now. A call counts in the bucket it was accepted in.`}
    legend={legend} table={table} note={expired}>{width=>{
    const plotW=width-PAD.left-PAD.right,plotH=HEIGHT-PAD.top-PAD.bottom,band=plotW/Math.max(1,data.length),barW=Math.max(1,band-2);
    const y=(v:number)=>PAD.top+plotH-(v/max)*plotH;
    const hovered=hover===undefined?undefined:data[hover];
    return <div className="insights-svg-wrap" onMouseLeave={()=>setHover(undefined)}>
      <svg width={width} height={HEIGHT} role="img" aria-label={`Bar chart of calls by outcome, ${data.length} buckets, peak ${Math.max(0,...data.map(b=>b.total))} calls`}>
        {ticks(max).map(t=><g key={t}><line className="grid" x1={PAD.left} x2={width-PAD.right} y1={y(t)} y2={y(t)}/>
          <text className="axis" x={PAD.left-8} y={y(t)} dy="0.32em" textAnchor="end">{compact(t)}</text></g>)}
        {data.map((b,i)=>{
          const x=PAD.left+i*band+1;let base=PAD.top+plotH;const parts:ReactNode[]=[];
          const shown=GROUPS.filter(g=>b.counts[g.id]>0);
          if(unavailableBefore!=null&&b.start<unavailableBefore)return <rect key={b.start} className="expired" x={x} y={PAD.top} width={barW} height={plotH}/>;
          shown.forEach((g,j)=>{
            // A 2px surface gap separates stacked segments; a non-zero segment stays at least 2px tall so it never vanishes.
            const gap=j>0?2:0,h=Math.max((b.counts[g.id]/max)*plotH,2+gap),top=j===shown.length-1;
            parts.push(<path key={g.id} className={`outcome-${g.id}`} d={bar(x,base-h+gap,barW,h-gap,top)}/>);base-=h;
          });
          return <g key={b.start}>{parts}</g>;
        })}
        <line className="baseline" x1={PAD.left} x2={width-PAD.right} y1={PAD.top+plotH} y2={PAD.top+plotH}/>
        {[0,Math.floor(data.length/2),data.length-1].filter((v,i,a)=>data[v]&&a.indexOf(v)===i).map(i=>
          <text key={i} className="axis" x={PAD.left+i*band+band/2} y={HEIGHT-8} textAnchor={i===0?'start':i===data.length-1?'end':'middle'}>{time(data[i]!.start,granularity)}</text>)}
        {data.map((b,i)=><rect key={`hit-${b.start}`} className="hit" x={PAD.left+i*band} y={PAD.top} width={band} height={plotH}
          tabIndex={-1} onMouseEnter={()=>setHover(i)} onFocus={()=>setHover(i)}/>)}
        {hovered&&<rect className="hover-band" x={PAD.left+hover!*band} y={PAD.top} width={band} height={plotH}/>}
      </svg>
      {hovered&&<Tooltip x={PAD.left+hover!*band} width={width}><strong>{time(hovered.start,granularity)} UTC</strong>
        {GROUPS.filter(g=>hovered.counts[g.id]>0).map(g=><span key={g.id}><i className={`swatch outcome-${g.id}`} aria-hidden="true"/>{g.label}: {hovered.counts[g.id]}</span>)}
        <span>Total: {hovered.total}</span></Tooltip>}
    </div>;
  }}</Frame>;
}

/** p50 and p95 of the execution duration per bucket, from merged histograms. A bucket below the sample floor has no
 *  point, so the line breaks there rather than dipping to zero. */
export function LatencyChart({data,granularity,bounds}:{data:Bucket[];granularity:InsightsGranularity;bounds:readonly number[]}){
  const [hover,setHover]=useState<number>();
  const series=[{id:'p50',label:'p50',q:0.5},{id:'p95',label:'p95',q:0.95}] as const;
  const values=series.map(s=>data.map(b=>percentile(b.histogram,bounds,s.q)));
  const max=niceMax(Math.max(0,...values.flat().filter((v):v is number=>v!==null)));
  const legend=series.map(s=><span key={s.id} className="insights-legend-item"><i className={`swatch latency-${s.id}`} aria-hidden="true"/>{s.label}</span>);
  const table=<div className="table-scroll"><table className="insights-table"><caption className="visually-hidden">Execution duration percentiles per {granularity.toLowerCase()}</caption>
    <thead><tr><th scope="col">Bucket (UTC)</th><th scope="col">p50</th><th scope="col">p95</th><th scope="col">Measured calls</th></tr></thead>
    <tbody>{data.map((b,i)=><tr key={b.start}><th scope="row">{time(b.start,granularity)}</th><td>{duration(values[0]![i])}</td><td>{duration(values[1]![i])}</td><td>{b.samples}</td></tr>)}</tbody></table></div>;
  return <Frame title="Execution duration" description="Duration of the attempt that produced each finished call. Values are histogram bucket upper bounds; p95 needs at least 20 measured calls in a bucket."
    legend={legend} table={table}>{width=>{
    const plotW=width-PAD.left-PAD.right,plotH=HEIGHT-PAD.top-PAD.bottom,band=plotW/Math.max(1,data.length);
    const x=(i:number)=>PAD.left+i*band+band/2,y=(v:number)=>PAD.top+plotH-(v/max)*plotH;
    const path=(vs:(number|null)[])=>vs.map((v,i)=>v===null?'':`${i===0||vs[i-1]===null?'M':'L'}${x(i)},${y(v)}`).join('');
    const hovered=hover===undefined?undefined:data[hover];
    return <div className="insights-svg-wrap" onMouseLeave={()=>setHover(undefined)}>
      <svg width={width} height={HEIGHT} role="img" aria-label="Line chart of p50 and p95 execution duration">
        {ticks(max).map(t=><g key={t}><line className="grid" x1={PAD.left} x2={width-PAD.right} y1={y(t)} y2={y(t)}/>
          <text className="axis" x={PAD.left-8} y={y(t)} dy="0.32em" textAnchor="end">{duration(t)}</text></g>)}
        <line className="baseline" x1={PAD.left} x2={width-PAD.right} y1={PAD.top+plotH} y2={PAD.top+plotH}/>
        {hovered&&<line className="crosshair" x1={x(hover!)} x2={x(hover!)} y1={PAD.top} y2={PAD.top+plotH}/>}
        {series.map((s,k)=>{
          const vs=values[k]!;let last=-1;vs.forEach((v,i)=>{if(v!==null)last=i;});
          return <g key={s.id}><path className={`line latency-${s.id}`} d={path(vs)}/>
            {vs.map((v,i)=>v!==null&&(vs[i-1]==null&&vs[i+1]==null||i===hover)?<circle key={i} className={`dot latency-${s.id}`} cx={x(i)} cy={y(v)} r={4}/>:null)}
            {last>=0&&<text className="direct-label" x={x(last)+8} y={y(vs[last]!)} dy="0.32em">{s.label}</text>}</g>;
        })}
        {[0,Math.floor(data.length/2),data.length-1].filter((v,i,a)=>data[v]&&a.indexOf(v)===i).map(i=>
          <text key={i} className="axis" x={x(i)} y={HEIGHT-8} textAnchor={i===0?'start':i===data.length-1?'end':'middle'}>{time(data[i]!.start,granularity)}</text>)}
        {data.map((b,i)=><rect key={`hit-${b.start}`} className="hit" x={PAD.left+i*band} y={PAD.top} width={band} height={plotH} onMouseEnter={()=>setHover(i)}/>)}
      </svg>
      {hovered&&<Tooltip x={x(hover!)} width={width}><strong>{time(hovered.start,granularity)} UTC</strong>
        {series.map((s,k)=><span key={s.id}><i className={`swatch latency-${s.id}`} aria-hidden="true"/>{s.label}: {values[k]![hover!]==null?'Too few samples':duration(values[k]![hover!])}</span>)}
        <span>Measured calls: {hovered.samples}</span></Tooltip>}
    </div>;
  }}</Frame>;
}

