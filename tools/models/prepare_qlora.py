# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Prepare pinned public OLO adapter weights for local Ollama; no training or publication."""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--adapter-repository',default='olo-labs/olo-qwen2.5-0.5b-hotfolder')
    parser.add_argument('--adapter-revision',default='e376fb40c313a8827c4d5ce6ca2abe2b6fe7a4c6')
    parser.add_argument('--base-repository',default='Qwen/Qwen2.5-0.5B-Instruct')
    parser.add_argument('--base-revision',default='7ae557604adf67be50417f59c2c2f167def9a775')
    args=parser.parse_args()
    identity={key:value for key,value in vars(args).items() if key!='output'}
    args.output.mkdir(parents=True,exist_ok=True)
    manifest=args.output/'source.json';merged=args.output/'merged'
    if manifest.exists():
        if json.loads(manifest.read_text(encoding='utf-8'))!=identity or not (merged/'config.json').exists():
            raise ValueError('Different or incomplete model preparation cache; choose a new output directory')
        print('Reusing source-qualified merged OLO model.',flush=True)
        convert(args.output, merged)
        return
    import torch
    from huggingface_hub import snapshot_download
    from transformers import AutoModelForCausalLM,AutoTokenizer
    from peft import PeftModel
    torch.set_num_threads(min(8,os.cpu_count() or 1))
    allowed=['*.json','*.safetensors','*.jinja','*.txt','*.model']
    base=snapshot_download(args.base_repository,revision=args.base_revision,local_dir=args.output/'base',allow_patterns=allowed,token=False)
    adapter=snapshot_download(args.adapter_repository,revision=args.adapter_revision,local_dir=args.output/'adapter',
        allow_patterns=['adapter_config.json','adapter_model.safetensors','tokenizer*','chat_template.jinja'],token=False)
    config=json.loads((Path(adapter)/'adapter_config.json').read_text(encoding='utf-8'))
    if config.get('base_model_name_or_path')!=args.base_repository or config.get('peft_type')!='LORA':
        raise ValueError('Adapter does not match the pinned base model')
    print('Merging the pinned OLO adapter on CPU; no host/GPU training dependencies.',flush=True)
    model=AutoModelForCausalLM.from_pretrained(base,dtype=torch.float32,device_map='cpu',trust_remote_code=False,use_safetensors=True)
    model=PeftModel.from_pretrained(model,adapter,is_trainable=False).merge_and_unload(safe_merge=True)
    model.save_pretrained(merged,safe_serialization=True)
    tokenizer=AutoTokenizer.from_pretrained(adapter,trust_remote_code=False)
    tokenizer.save_pretrained(merged)
    manifest.write_text(json.dumps(identity,indent=2)+'\n',encoding='utf-8')
    convert(args.output, merged)


def convert(output, merged):
    target=output/'model-f16.gguf'
    if not target.exists():
        temporary=output/'model-f16.partial.gguf'
        subprocess.run([sys.executable,os.environ['OLO_GGUF_CONVERTER'],str(merged),
            '--outfile',str(temporary),'--outtype','f16'],check=True)
        temporary.replace(target)
    print('Merged OLO model prepared for Ollama GGUF import.',flush=True)


if __name__=='__main__':main()
