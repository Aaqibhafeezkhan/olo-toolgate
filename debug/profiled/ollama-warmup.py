# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Non-interactive preload through Ollama's loopback API; no prompt or credentials."""
import argparse
import json
import urllib.request


def preload(model,timeout=180,opener=None):
    if not 1<=timeout<=1800:raise ValueError('Use a bounded model preload timeout')
    opener=opener or urllib.request.build_opener(urllib.request.ProxyHandler({}))
    request=urllib.request.Request('http://127.0.0.1:11434/api/generate',
        json.dumps(dict(model=model,prompt='',stream=False,keep_alive='24h',options={'num_ctx':4096})).encode(),
        {'Content-Type':'application/json'},method='POST')
    with opener.open(request,timeout=timeout) as response:
        raw=response.read(65537)
        if response.status!=200 or len(raw)>65536:raise ValueError('Ollama preload response refused')
    value=json.loads(raw)
    if not isinstance(value,dict) or value.get('model')!=model or value.get('done') is not True:
        raise ValueError('Ollama did not confirm model preload')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model',required=True)
    parser.add_argument('--timeout',type=int,default=180)
    args=parser.parse_args()
    try:preload(args.model,args.timeout)
    except Exception:
        raise SystemExit('Ollama model preload failed or timed out; inspect Ollama logs.') from None
    print('Model preloaded: '+args.model,flush=True)


if __name__=='__main__':main()
