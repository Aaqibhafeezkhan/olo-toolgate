# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Render the captioned README explainer. Requires Pillow and imageio-ffmpeg."""
from pathlib import Path
import subprocess

from PIL import Image, ImageDraw, ImageFont
import imageio_ffmpeg

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/media"
WIDTH, HEIGHT, FPS, SECONDS = 1280, 720, 15, 36
BG, CARD, WHITE = "#0c1222", "#18243b", "#f5f7fc"
MUTED, PURPLE, CYAN = "#bac7de", "#ab96ff", "#65e4df"
SCENES = [
    ("Zero Trust for Agentic AI.", "Secure Every Tool Boundary.", ["AI agents", "OLO ToolGate", "Your tools"],
     "Govern every tool call with identity, policy enforcement, least-privilege access and end-to-end auditability."),
    ("One request. Clear boundaries.", "Example: an agent writes a file", ["Agent request", "Gateway checks", "Device executes"],
     "An agent calls hotfolder.write_text. The Gateway checks access before a protected effect."),
    ("Access starts with groups.", "Teams + Agent Groups + Tool Groups + Device Groups", ["People / agents", "Group grants", "Tools / devices"],
     "Groups hold access mappings. Default groups grant no access; new verified users start disabled."),
    ("Allow. Ask. Block.", "Policy applies after the required grants", ["ALLOW", "ASK", "BLOCK"],
     "Allow permits an authorized action. Ask requires independent approval. Block prevents execution."),
    ("Manage it. See what happened.", "The console and endpoint client work together", ["Admin console", "Device status", "Activity / audit"],
     "Manage groups and devices in the console. The Windows tray shows command progress and activity."),
    ("Your first PR can be small.", "Start with a glossary, example or short guide", ["Pick an issue", "Make one change", "Open a PR"],
     "Visit github.com/olo-labs/olo-toolgate. Choose a good first issue and help improve the docs."),
]


def font(size, bold=False):
    candidates = [
        Path("C:/Windows/Fonts") / ("segoeuib.ttf" if bold else "segoeui.ttf"),
        Path("/usr/share/fonts/truetype/dejavu") / ("DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"),
    ]
    return ImageFont.truetype(str(next(p for p in candidates if p.exists())), size)


def wrap(draw, text, face, width):
    lines, line = [], ""
    for word in text.split():
        candidate = (line + " " + word).strip()
        if draw.textlength(candidate, font=face) > width and line:
            lines.append(line)
            line = word
        else:
            line = candidate
    return lines + [line]


def frame(t):
    index = min(int(t // 6), len(SCENES) - 1)
    local = t % 6
    title, subtitle, labels, caption = SCENES[index]
    im = Image.new("RGB", (WIDTH, HEIGHT), BG)
    draw = ImageDraw.Draw(im)
    # A quiet grid and consistent branding keep the diagram legible on small screens.
    for x in range(0, WIDTH, 80):
        draw.line((x, 0, x, HEIGHT), fill="#111b2d")
    for y in range(0, HEIGHT, 80):
        draw.line((0, y, WIDTH, y), fill="#111b2d")
    logo = Image.open(ROOT / "apps/admin-ui/src/assets/olo.png").convert("RGBA")
    logo.thumbnail((82, 42))
    draw.rounded_rectangle((48, 35, 145, 91), radius=12, fill=WHITE)
    im.paste(logo, (96 - logo.width // 2, 63 - logo.height // 2), logo)
    draw.text((163, 43), "ToolGate", font=font(28, True), fill=WHITE)
    draw.text((1030, 50), f"{index + 1:02d} / 06", font=font(22), fill=MUTED)
    draw.text((64, 146), title, font=font(51, True), fill=WHITE)
    draw.text((66, 220), subtitle, font=font(26), fill=CYAN)
    rise = round(22 * (1 - min(local / .7, 1)) ** 3)
    for n, label in enumerate(labels):
        x, y = 64 + n * 394, 326 + rise
        active = min(int(local / 1.7), 2) == n
        accent = ["#65e4df", "#ffc870", "#ff8c9d"][n] if index == 3 else PURPLE
        draw.rounded_rectangle((x, y, x + 360, y + 138), radius=20,
                               fill=CARD, outline=accent if active else "#33435e", width=3)
        draw.text((x + 25, y + 19), f"0{n + 1}", font=font(18), fill=accent)
        draw.text((x + 25, y + 61), label, font=font(27, True), fill=WHITE)
        if n < 2 and index != 3:
            draw.line((x + 367, y + 70, x + 387, y + 70), fill=CYAN, width=3)
            draw.polygon([(x + 389, y + 70), (x + 381, y + 65), (x + 381, y + 75)], fill=CYAN)
    draw.rounded_rectangle((48, 523, 1232, 653), radius=16, fill="#111d32")
    for n, line in enumerate(wrap(draw, caption, font(25), 1110)):
        draw.text((76, 546 + n * 37), line, font=font(25), fill=WHITE)
    draw.text((64, 679), "olo-labs / olo-toolgate", font=font(16), fill=MUTED)
    draw.text((1070, 679), "Apache-2.0", font=font(16), fill=MUTED)
    draw.rectangle((0, 714, round(WIDTH * t / SECONDS), 719), fill=PURPLE)
    return im


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    encoder = imageio_ffmpeg.get_ffmpeg_exe()
    video = OUT / "toolgate-overview.mp4"
    args = [encoder, "-y", "-loglevel", "error", "-f", "rawvideo", "-pixel_format", "rgb24",
            "-video_size", f"{WIDTH}x{HEIGHT}", "-framerate", str(FPS), "-i", "pipe:0",
            "-an", "-c:v", "libx264", "-crf", "25", "-pix_fmt", "yuv420p",
            "-movflags", "+faststart", str(video)]
    process = subprocess.Popen(args, stdin=subprocess.PIPE)
    try:
        for i in range(SECONDS * FPS):
            process.stdin.write(frame(i / FPS).tobytes())
    finally:
        process.stdin.close()
    if process.wait() != 0:
        raise RuntimeError("Video encoding failed")
    frame(2).save(OUT / "toolgate-overview.png")
    subprocess.run([encoder, "-y", "-loglevel", "error", "-i", str(video), "-filter_complex",
                    "fps=4,scale=640:-1,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer",
                    "-loop", "0", str(OUT / "toolgate-overview.gif")], check=True)
    transcript = ["WEBVTT", ""]
    for i, (_, _, _, caption) in enumerate(SCENES):
        transcript += [f"00:00:{i * 6:02d}.000 --> 00:00:{(i + 1) * 6:02d}.000", caption, ""]
    (OUT / "toolgate-overview.vtt").write_text("\n".join(transcript), encoding="utf-8")
    for path in sorted(OUT.glob("toolgate-overview.*")):
        print(f"{path.name}: {path.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
