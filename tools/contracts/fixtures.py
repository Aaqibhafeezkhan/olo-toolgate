# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Maintain structural corpus coverage without inventing runtime permission fixtures."""
import argparse,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def outputs():
 defs={n:s for p in (ROOT/'packages/contracts/schemas/v1').glob('*.json') for n,s in json.loads(p.read_text(encoding='utf-8'))['$defs'].items()}
 p=ROOT/'tests/fixtures/contracts/v1/valid.json';fixtures={n:v for n,v in json.loads(p.read_text(encoding='utf-8')).items() if n in defs}
 def sample(s):
  if 'const' in s:return s['const']
  if '$ref' in s:
   n=s['$ref'].split('/')[-1];return fixtures[n] if n in fixtures else sample(defs[n])
  if 'enum' in s:return s['enum'][0]
  t=s.get('type')
  if t=='object':return {n:sample(s['properties'][n]) for n in s.get('required',[])}
  if t=='array':return [sample(s['items']) for _ in range(s.get('minItems',0))]
  if t=='boolean':return False
  if t in ('integer','number'):return s.get('minimum',0)
  if t=='string':return 'a'*64 if '64}' in s.get('pattern','') else 'example'
  raise ValueError('Explicit fixture required for '+str(s))
 for n,s in defs.items():
  if n not in fixtures:fixtures[n]=sample(s)
 models=sorted(n for n,s in defs.items() if s.get('type')=='object' or 'enum' in s);out={p:json.dumps(dict(sorted(fixtures.items())),indent=2)+'\n'}
 p=ROOT/'packages/contracts/rust/tests/roundtrip.rs';s=p.read_text(encoding='utf-8');a=s.index('    check!(');b=s.index('    );',a)+len('    );');out[p]=s[:a]+'    check!(\n'+''.join('        '+n+',\n' for n in models)+'    );'+s[b:]
 p=ROOT/'packages/contracts/java/src/test/java/io/ololabs/toolgate/contracts/ContractRoundTripTest.java';out[p]=re.sub(r'assertEquals\(\d+, checked\);',f'assertEquals({len(models)}, checked);',p.read_text(encoding='utf-8'))
 p=ROOT/'packages/contracts/php/tests/roundtrip.php';out[p]=re.sub(r'if \(\$count !== \d+\)',f'if ($count !== {len(models)})',p.read_text(encoding='utf-8'))
 defs2={n:s for p in (ROOT/'packages/contracts/schemas/v2').glob('*.json') for n,s in json.loads(p.read_text(encoding='utf-8'))['$defs'].items()}
 # v2: tests/fixtures/contracts/v2/valid.json is the hand-written corpus; aliases are Rust-only types.
 structural=sorted(n for n,s in defs2.items() if s.get('type')=='object' or 'enum' in s);aliases=sorted(n for n,s in defs2.items() if '$ref' in s)
 p=ROOT/'packages/contracts/rust/tests/roundtrip_v2.rs';s=p.read_text(encoding='utf-8');a=s.index('    check!(');b=s.index('    );',a)+len('    );');out[p]=s[:a]+'    check!(\n'+''.join('        '+n+',\n' for n in sorted(structural+aliases))+'    );'+s[b:]
 p=ROOT/'packages/contracts/java/src/test/java/io/ololabs/toolgate/contracts/ContractV2RoundTripTest.java';out[p]=re.sub(r'MODELS = Set\.of\([^)]*\);','MODELS = Set.of(\n'+',\n'.join('        "'+n+'"' for n in structural)+');',p.read_text(encoding='utf-8'))
 p=ROOT/'packages/contracts/php/tests/roundtrip_v2.php';out[p]=re.sub(r'\$models = \[[^\]]*\];','$models = [\n'+',\n'.join("    '"+n+"'" for n in structural)+'\n];',p.read_text(encoding='utf-8'))
 return out

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
 for p,value in outputs().items():
  if args.check:
   if p.read_text(encoding='utf-8')!=value:raise SystemExit('Corpus drift: '+p.relative_to(ROOT).as_posix())
  else:p.write_text(value,encoding='utf-8',newline='\n')
 print('Structural contract corpus coverage verified' if args.check else 'Structural contract corpus updated')
if __name__=='__main__':main()
