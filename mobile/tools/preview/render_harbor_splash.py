#!/usr/bin/env python3
"""Offline artwork preview from compiled production timing and shared native geometry.

Run after assembleDebug. This is not a device capture or a performance benchmark.
Requires Java, ImageMagick and ffmpeg; does not change any app/source bitmap.
"""
import base64
import concurrent.futures
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/redesign/evidence/harbor-splash-2026-09-22"


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, stdout=subprocess.DEVNULL)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    classes = ROOT / "app/build/tmp/kotlin-classes/debug"
    stdlib = next((Path.home() / ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib").glob("**/kotlin-stdlib-2.1.20.jar"))
    run("java", "-cp", os.pathsep.join([str(classes), str(stdlib)]),
        ROOT / "tools/preview/ExportHarborFrames.java", OUT / "timeline.json")
    frames = json.loads((OUT / "timeline.json").read_text())
    paths = {node.get("name"): node.text for node in ET.parse(ROOT / "app/src/main/res/values/brand_paths.xml").getroot()}
    dock, boat = paths["brand_dock_path"], paths["brand_boat_path"]
    source = ROOT / "app/src/main/res/drawable-nodpi/copenhagen_splash.webp"
    bitmap = "data:image/webp;base64," + base64.b64encode(source.read_bytes()).decode()

    def shadow(path):
        return f'''<g transform="translate(0 18)" fill-rule="evenodd" stroke-linejoin="round">
          <path d="{path}" fill="none" stroke="#0039CF" stroke-width="46" opacity=".07"/>
          <path d="{path}" fill="none" stroke="#0039CF" stroke-width="22" opacity=".13"/>
          <path d="{path}" fill="#0039CF" opacity=".18"/></g>'''

    def svg(f):
        arrival_alpha = max(0, .2 * math.sin(math.pi * f["arrival"]))
        return f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="720" height="960" viewBox="0 0 720 960">
        <defs>
          <image id="original" width="1254" height="1254" xlink:href="{bitmap}"/>
          <clipPath id="card"><rect width="1254" height="1254" rx="351.12"/></clipPath>
          <clipPath id="dock"><path d="{dock}"/></clipPath>
          <clipPath id="boat"><path d="{boat}" clip-rule="evenodd"/></clipPath>
          <linearGradient id="sea" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="1254" y2="1254"><stop stop-color="#1069FC"/><stop offset="1" stop-color="#0751FD"/></linearGradient>
          <radialGradient id="day" gradientUnits="userSpaceOnUse" cx="0" cy="0" r="1254"><stop stop-color="#3887F7" stop-opacity=".4"/><stop offset="1" stop-color="#3887F7" stop-opacity="0"/></radialGradient>
        </defs>
        <rect width="720" height="960" fill="#F5F6F8"/>
        <text x="360" y="48" text-anchor="middle" fill="#7A8291" font-family="DejaVu Sans" font-size="15">Harbor arrival · motion preview</text>
        <g transform="translate(144 210) scale(0.3444976077)" opacity="{f['artworkAlpha']}">
          <g clip-path="url(#card)">
            <rect width="1254" height="1254" fill="url(#sea)"/>
            <rect width="1254" height="1254" fill="url(#day)"/>
            <circle cx="500" cy="625" r="{42 + 88 * f['arrival']}" fill="none" stroke="white" stroke-width="5" opacity="{arrival_alpha}"/>
            {shadow(dock)}
            <use xlink:href="#original" clip-path="url(#dock)"/>
            <g transform="translate({f['boatX']} {f['boatY']}) rotate({f['bankDegrees']} 750 623)">
              <path d="M1032,578C1080,572 1128,543 1178,546M1032,668C1080,674 1128,703 1178,700" fill="none" stroke="white" stroke-width="7" stroke-linecap="round" opacity="{f['wakeAlpha']}"/>
              <path d="M1060,624H1175" fill="none" stroke="white" stroke-width="5" stroke-linecap="round" opacity="{f['wakeAlpha'] * .55}"/>
              {shadow(boat)}
              <use xlink:href="#original" clip-path="url(#boat)"/>
            </g>
            <use xlink:href="#original" opacity="{f['originalLogoAlpha']}"/>
          </g>
        </g>
        <text x="360" y="738" text-anchor="middle" fill="#111318" font-family="DejaVu Sans" font-size="44" font-weight="bold" opacity="{f['wordmarkAlpha']}">Copenhagen</text>
        <text x="360" y="920" text-anchor="middle" fill="#7A8291" font-family="DejaVu Sans" font-size="13">Production timeline + artwork · not a device recording</text>
        </svg>'''

    with tempfile.TemporaryDirectory(prefix="copenhagen-harbor-render-") as temp:
        temp = Path(temp)
        def render(item):
            i, f = item
            vector = temp / f"frame-{i:03d}.svg"
            vector.write_text(svg(f))
            run("magick", "-background", "none", vector, temp / f"frame-{i:03d}.png")
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as workers:
            list(workers.map(render, enumerate(frames)))
        encoders = subprocess.check_output(["ffmpeg", "-hide_banner", "-encoders"], stderr=subprocess.DEVNULL, text=True)
        codec = ["-c:v", "libx264", "-crf", "18"] if "libx264" in encoders else ["-c:v", "libopenh264", "-b:v", "3M"]
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-framerate", "30", "-i", temp / "frame-%03d.png",
            *codec, "-pix_fmt", "yuv420p", "-movflags", "+faststart", OUT / "harbor-splash-preview.mp4")
        storyboard = []
        for i in [6, 12, 24, 42, 62]:
            target = OUT / f"frame-{i:03d}.png"
            shutil.copy2(temp / f"frame-{i:03d}.png", target)
            thumb = temp / f"thumb-{i:03d}.png"
            run("magick", target, "-resize", "240x320", thumb)
            storyboard.append(thumb)
        run("magick", *storyboard, "+append", OUT / "storyboard.png")
    print(OUT / "harbor-splash-preview.mp4")


if __name__ == "__main__":
    main()
