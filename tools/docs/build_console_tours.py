# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Rebuild the README console tours from checksum-verified original browser captures (Pillow)."""
import hashlib
import json
import textwrap
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageStat

ROOT=Path(__file__).resolve().parents[2]/'docs/media'


def main():
    inventory=json.loads((ROOT/'screenshots.json').read_text(encoding='utf-8'))
    shots=inventory['screens']
    plan=json.loads((ROOT/'tours.json').read_text(encoding='utf-8'))
    sources={s['file']:s for s in shots}
    if len(sources)!=len(shots):raise ValueError('Duplicate capture inventory entry')
    individual_only=set(plan.get('individualOnly',[]))
    if not individual_only<=sources.keys():raise ValueError('Unknown individual-only capture')
    expected=sources.keys()-individual_only
    included=[file for tour in plan['tours'] for file in tour['frames']]
    if set(included)!=expected:raise ValueError('Tour coverage differs from the eligible capture inventory')
    for entry in shots:
        path=(ROOT/entry['file']).resolve()
        if not path.is_relative_to(ROOT.resolve()) or path.suffix not in ('.jpg','.png'):raise ValueError('Invalid capture path')
        if hashlib.sha256(path.read_bytes()).hexdigest()!=entry['sha256']:raise ValueError('Capture checksum mismatch')
        with Image.open(path) as original:
            if max(ImageStat.Stat(original.convert('RGB')).stddev)<3:raise ValueError('Blank or unrendered capture: '+entry['file'])
    try:font=ImageFont.truetype('DejaVuSans.ttf',17)
    except OSError:font=ImageFont.load_default(size=17)
    guide=['# OLO demo flows','',
        f"Real browser captures from {inventory['capturedDate']}. All image links open saved originals.",
        inventory['scope'], '',
        'Slides are navigation aids, not continuous screen recordings. Empty queues remain empty; no approval or outcome was invented.', '',
        '[Capture manifest](screenshots.json) · [Run and cloud evidence](olo-2026-11/evidence.json) · [Historical captures](HISTORICAL.md)', '']
    for tour in plan['tours']:
        name=tour['file']
        if Path(name).name!=name or not name.endswith('.gif'):raise ValueError('Invalid tour filename')
        entries=[sources[file] for file in tour['frames']]
        if len(set(tour['frames']))!=len(entries):raise ValueError('Duplicate frame in tour: '+name)
        if not plan['minimumFrames']<=len(entries)<=plan['maximumFrames']:raise ValueError('Invalid frame count: '+name)
        frames=[]
        for i,entry in enumerate(entries,1):
            path=(ROOT/entry['file']).resolve()
            with Image.open(path) as original:
                image=original.convert('RGB')
                image.thumbnail((1024,576),Image.Resampling.LANCZOS)
                frame=Image.new('RGB',(1024,626),'#101827')
                frame.paste(image,((1024-image.width)//2,(576-image.height)//2))
                caption=f"{i:02}/{len(entries):02}  {entry['title']}"
                ImageDraw.Draw(frame).multiline_text((15,580),'\n'.join(textwrap.wrap(caption,95)),font=font,spacing=2,fill='#f5f7fa')
                frames.append(frame.quantize(colors=128,method=Image.Quantize.MEDIANCUT))
        frames[0].save(ROOT/name,save_all=True,append_images=frames[1:],duration=3000,loop=0,optimize=False)
        with Image.open(ROOT/name) as result:
            if result.n_frames!=len(entries):raise ValueError('GIF frame count differs from the tour plan')
        print(name,len(frames),(ROOT/name).stat().st_size)
        guide.extend([f"## {tour['title']}",'',f"[Animated tour]({name})",'',
            '| Step | Screen | Capture |','| --- | --- | --- |'])
        for i,entry in enumerate(entries,1):
            source=entry['capturedAt']
            guide.append(f"| {i} | [{entry['title']}]({entry['file']}) | {source} |")
        guide.append('')
    guide.extend(['## Individual full-page views',''])
    guide.extend(f"- [{sources[file]['title']}]({file})" for file in sorted(individual_only))
    (ROOT/'TOURS.md').write_text('\n'.join(guide)+'\n',encoding='utf-8',newline='\n')


if __name__=='__main__':main()
