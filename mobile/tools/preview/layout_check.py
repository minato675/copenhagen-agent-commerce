#!/usr/bin/env python3
"""Repeatable native layout evidence; owns emulator only and restores its display settings."""
import json
from pathlib import Path
import device as d

OUT = Path('docs/redesign/evidence/layout')
OUT.mkdir(parents=True, exist_ok=True)
ID = '11111111-1111-4111-8111-111111111111'

def settings(key, value): d.adb('shell', 'settings', 'put', 'system', key, str(value))
def geometry(width, height): d.adb('shell', 'wm', 'size', f'{round(width*2.625)}x{round(height*2.625)}')
def review():
    d.adb('shell', 'am', 'start', '-n', d.ACTIVITY, '--es', 'approvalId', ID)
    d.wait_for(['승인 요청', '요청 총액'], timeout=30)

results=[]
try:
    settings('font_scale',1.0)
    d.control('reset', {})
    for width,height in [(320,760),(360,800),(390,844),(430,900),(800,610)]:
        geometry(width,height)
        review()
        d.capture(str(OUT/f'review-{width}.png'), ['승인 요청','42,800원'])
        results.append(dict(width_dp=width,height_dp=height,font_scale=1,expected='Actual review, exact amount; native reflow', artifact=f'review-{width}.png'))
    geometry(320,568)
    settings('font_scale',2.0)
    d.control('config',{'long':True,'amount_minor':9223372036854775807})
    d.adb('shell','am','start','-n',d.ACTIVITY,'--es','approvalId',ID)
    long_merchant = '코펜하겐 라이프스타일 스튜디오 한정판 컬렉션 온라인 스토어'
    d.wait_for(['승인 요청', long_merchant])
    d.capture(str(OUT/'review-320-font200-long.png'), ['승인 요청', long_merchant])
    for i in range(3):
        d.adb('shell','input','swipe','420','1190','420','360','350')
        d.capture(str(OUT/f'review-320-font200-scroll-{i}.png'), ['승인 요청'])
    results.append(dict(width_dp=320,height_dp=568,font_scale=2,large_amount='9223372036854775807',long_identity=True,artifacts='review-320-font200-*',expected='Native scroll exposes full identity, exact amount and actions'))
finally:
    settings('font_scale',1.0)
    d.adb('shell','wm','size','reset')
    d.control('reset',{})
    (OUT/'matrix.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
