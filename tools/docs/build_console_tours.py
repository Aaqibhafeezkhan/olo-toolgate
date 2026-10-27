# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Rebuild the README console tours from checksum-verified original browser captures (Pillow)."""
import hashlib
import json
import textwrap
from pathlib import Path
from urllib.parse import urlsplit
from PIL import Image, ImageDraw, ImageFont, ImageStat

ROOT=Path(__file__).resolve().parents[2]/'docs/media'


def main():
    shots=json.loads((ROOT/'screenshots.json').read_text(encoding='utf-8'))['screens']
    try:font=ImageFont.truetype('DejaVuSans.ttf',17)
    except OSError:font=ImageFont.load_default(size=17)
    def route(shot):return urlsplit(shot['url']).fragment.split('?',1)[0]
    console=[s for s in shots if s['group']=='console' and '-full' not in s['file'] and '-light' not in s['file']]
    operator=[s for s in shots if s['group']=='operator' and '-full' not in s['file']]
    tours={
        'console-tour.gif':console,
        'operator-insights-tour.gif':operator,
        'device-flow.gif':[s for s in console if route(s) in {'devices','deviceGroups','deviceEvidence','enroll','fleet'}],
        'console-tools-tour.gif':[s for s in console if route(s) in {'tools','toolGroups','server-packages','builder','test'}],
        'console-people-access.gif':[s for s in console if route(s) in {'users','teams','roles','grants','delegations','policies'}],
        'console-devices-agents.gif':[s for s in console if route(s) in {'devices','agents','agentGroups','mapping','workloadBindings'}],
        'console-administration.gif':[s for s in console if route(s) in {'config-server','config-device','config-backup','config-reviews','about'}],
        'console-light.gif':[s for s in shots if '-light' in s['file']],
    }
    for name,entries in tours.items():
        if not entries:raise ValueError('Missing captures for '+name)
        frames=[]
        for i,entry in enumerate(entries,1):
            path=ROOT/entry['file']
            if hashlib.sha256(path.read_bytes()).hexdigest()!=entry['sha256']:raise ValueError('Capture checksum mismatch')
            with Image.open(path) as original:
                image=original.convert('RGB')
                if max(ImageStat.Stat(image).stddev)<3:raise ValueError('Blank or unrendered capture: '+entry['file'])
                image.thumbnail((1024,576),Image.Resampling.LANCZOS)
                frame=Image.new('RGB',(1024,626),'#101827')
                frame.paste(image,((1024-image.width)//2,(576-image.height)//2))
                caption=f"{i:02}/{len(entries):02}  {entry['title']}"
                ImageDraw.Draw(frame).multiline_text((15,580),'\n'.join(textwrap.wrap(caption,95)),font=font,spacing=2,fill='#f5f7fa')
                frames.append(frame.quantize(colors=128,method=Image.Quantize.MEDIANCUT))
        frames[0].save(ROOT/name,save_all=True,append_images=frames[1:],duration=3000,loop=0,optimize=False)
        print(name,len(frames),(ROOT/name).stat().st_size)


if __name__=='__main__':main()
