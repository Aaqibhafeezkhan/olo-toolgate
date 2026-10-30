# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Rebuild the README New Relic slideshow from the saved full-page captures (Pillow)."""
import textwrap
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[2]/'docs/media'
OUTPUT='newrelic-traces.gif'
SLIDES=[
    ('screenshots/newrelic-traces-overview-full.jpg','Trace overview: Hello World tool calls from the ToolGate service'),
    ('screenshots/newrelic-call-waterfall-full.jpg','Call waterfall: gateway request, authorization, dispatch and Tool Host execution'),
    ('screenshots/newrelic-execution-attributes-full.jpg','Execution attributes: span correlation with the ToolGate execution'),
]


def main():
    try:font=ImageFont.truetype('DejaVuSans.ttf',17)
    except OSError:font=ImageFont.load_default(size=17)
    # Captures are portrait-ish 4:3, so scale to the full frame width and caption flush with the image edge.
    images=[]
    for file,_ in SLIDES:
        with Image.open(ROOT/file) as original:
            image=original.convert('RGB')
            images.append(image.resize((1024,round(image.height*1024/image.width)),Image.Resampling.LANCZOS))
    height=max(image.height for image in images)
    frames=[]
    for i,((_,title),image) in enumerate(zip(SLIDES,images),1):
        frame=Image.new('RGB',(1024,height+50),'#101827')
        frame.paste(image,(0,0))
        caption=f"{i:02}/{len(SLIDES):02}  {title}"
        ImageDraw.Draw(frame).multiline_text((15,height+12),'\n'.join(textwrap.wrap(caption,95)),font=font,spacing=2,fill='#f5f7fa')
        frames.append(frame.quantize(colors=128,method=Image.Quantize.MEDIANCUT))
    frames[0].save(ROOT/OUTPUT,save_all=True,append_images=frames[1:],duration=3000,loop=0,optimize=False)
    print(OUTPUT,len(frames),(ROOT/OUTPUT).stat().st_size)


if __name__=='__main__':main()
