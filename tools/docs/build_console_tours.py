# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Rebuild the README console tours from checksum-verified original browser captures (Pillow)."""
import hashlib
import json
import textwrap
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageStat

ROOT=Path(__file__).resolve().parents[2]/'docs/media'


def main():
    shots=json.loads((ROOT/'screenshots.json').read_text(encoding='utf-8'))['screens']
    plan=json.loads((ROOT/'tours.json').read_text(encoding='utf-8'))
    if not 3<=len(plan['tours'])<=4:raise ValueError('Use three or four focused demo sets')
    historical={s['file'] for s in plan['historicalDeviceCaptures']}
    sources={s['file']:s for s in [*shots,*plan['historicalDeviceCaptures']]}
    individual_only=set(plan.get('individualOnly',[]))
    if not individual_only<=sources.keys():raise ValueError('Unknown individual-only capture')
    expected=sources.keys()-individual_only
    included=[file for tour in plan['tours'] for file in tour['frames']]
    if len(included)!=len(set(included)):raise ValueError('A capture appears in more than one tour')
    if set(included)!=expected:raise ValueError('Tour coverage differs from the eligible capture inventory')
    try:font=ImageFont.truetype('DejaVuSans.ttf',17)
    except OSError:font=ImageFont.load_default(size=17)
    guide=['# ToolGate demo flows','',
        'Four focused sets with 10–15 screens each. All links open the original saved images.',
        'Earlier device/client captures are explicitly marked; current console/operator captures are from October 27, 2026.', '']
    for tour in plan['tours']:
        name=tour['file']
        if Path(name).name!=name or not name.endswith('.gif'):raise ValueError('Invalid tour filename')
        entries=[sources[file] for file in tour['frames']]
        if not 10<=len(entries)<=15:raise ValueError('Each demo must contain 10-15 frames: '+name)
        frames=[]
        for i,entry in enumerate(entries,1):
            path=(ROOT/entry['file']).resolve()
            if not path.is_relative_to(ROOT.resolve()) or path.suffix!='.jpg':raise ValueError('Invalid capture path')
            if hashlib.sha256(path.read_bytes()).hexdigest()!=entry['sha256']:raise ValueError('Capture checksum mismatch')
            with Image.open(path) as original:
                image=original.convert('RGB')
                if max(ImageStat.Stat(image).stddev)<3:raise ValueError('Blank or unrendered capture: '+entry['file'])
                image.thumbnail((1024,576),Image.Resampling.LANCZOS)
                frame=Image.new('RGB',(1024,626),'#101827')
                frame.paste(image,((1024-image.width)//2,(576-image.height)//2))
                provenance='Earlier capture: ' if entry['file'] in historical else ''
                caption=f"{i:02}/{len(entries):02}  {provenance}{entry['title']}"
                ImageDraw.Draw(frame).multiline_text((15,580),'\n'.join(textwrap.wrap(caption,95)),font=font,spacing=2,fill='#f5f7fa')
                frames.append(frame.quantize(colors=128,method=Image.Quantize.MEDIANCUT))
        frames[0].save(ROOT/name,save_all=True,append_images=frames[1:],duration=3000,loop=0,optimize=False)
        with Image.open(ROOT/name) as result:
            if result.n_frames!=len(entries):raise ValueError('GIF frame count differs from the tour plan')
        print(name,len(frames),(ROOT/name).stat().st_size)
        guide.extend([f"## {tour['title']}",'',f"[Animated tour]({name})",'',
            '| Step | Screen | Capture |','| --- | --- | --- |'])
        for i,entry in enumerate(entries,1):
            source='Earlier capture' if entry['file'] in historical else 'October 27 console/operator'
            guide.append(f"| {i} | [{entry['title']}]({entry['file']}) | {source} |")
        guide.append('')
    guide.extend(['## Individual full-page views',''])
    guide.extend(f"- [{sources[file]['title']}]({file})" for file in sorted(individual_only))
    (ROOT/'TOURS.md').write_text('\n'.join(guide)+'\n',encoding='utf-8')


if __name__=='__main__':main()
