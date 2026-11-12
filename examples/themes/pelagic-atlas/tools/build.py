# Copyright 2023-2025 RWPP contributors
# 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束。
# Use of this source code is governed by the GNU AGPLv3 license.
# https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
"""Rebuild the original, font-free vector artwork and deterministic theme ZIP.

Requires Python 3.11+, Pillow and resvg-py. No fonts, downloads or game assets.
"""
from pathlib import Path
import hashlib
import math
import tomllib
import zipfile
import resvg_py
from PIL import Image
from room_art import build_room

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[2]
INK, RED, PAPER, BLUE = '#183A49', '#B84732', '#F4EDDF', '#C9DBDF'

def svg(w, h, body):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}"><!-- Original Pelagic Atlas artwork; AGPL-3.0-or-later. -->{body}</svg>'

def line(x1,y1,x2,y2,color=INK,width=1,extra=''):
    return f'<path d="M{x1} {y1}L{x2} {y2}" fill="none" stroke="{color}" stroke-width="{width}" {extra}/>'

def circle(x,y,r,color=INK,width=1,fill='none'):
    return f'<circle cx="{x}" cy="{y}" r="{r}" stroke="{color}" stroke-width="{width}" fill="{fill}"/>'

def compass(x,y,r):
    out = circle(x,y,r,INK,2) + circle(x,y,r*.83,INK,1)
    for a in range(0,360,15):
        t=math.radians(a)
        ri=r*(.86 if a%45 else .76)
        out+=line(x+ri*math.cos(t),y+ri*math.sin(t),x+r*.95*math.cos(t),y+r*.95*math.sin(t),INK,2 if a%45==0 else 1)
    out+=f'<path d="M{x} {y-r*.7}L{x+r*.16} {y}L{x} {y+r*.7}L{x-r*.16} {y}Z" fill="{INK}"/>'
    out+=f'<path d="M{x} {y-r*.7}L{x+r*.16} {y}L{x} {y}Z" fill="{RED}"/>'
    out+=circle(x,y,r*.07,PAPER,2,RED)
    return out

# Original monoline geometric alphabet; no font dependency or font embedding.
GLYPHS={
 'P':'M0 100V0H42L58 15V36L42 50H0',
 'E':'M58 0H0V100H58 M0 49H46',
 'L':'M0 0V100H58',
 'A':'M0 100L29 0L58 100 M12 62H46',
 'G':'M58 20L42 0H16L0 18V82L16 100H58V54H32',
 'I':'M0 0H40 M20 0V100 M0 100H40',
 'C':'M58 18L42 0H16L0 18V82L16 100H42L58 82',
 'T':'M0 0H60 M30 0V100',
 'S':'M58 16L42 0H16L0 16V36L16 50H42L58 64V84L42 100H16L0 84',
 'R':'M0 100V0H42L58 15V36L42 50H0 M30 50L60 100',
 'W':'M0 0L12 100L34 44L56 100L68 0',
 'J':'M0 0H58V80L40 100H18L0 82',
}

def word(text,x,y,size,color=INK,tracking=22,stroke=5):
    out=''
    for c in text:
        if c==' ':
            x+=size*.5
            continue
        out+=f'<path d="{GLYPHS[c]}" transform="translate({x} {y}) scale({size/100})" fill="none" stroke="{color}" stroke-width="{stroke}" stroke-linejoin="miter"/>'
        x+=(40 if c=='I' else 68 if c=='W' else 60)*size/100+tracking
    return out

def emit(name,w,h,body):
    source=ROOT/'source'/(name+'.svg')
    source.parent.mkdir(parents=True,exist_ok=True)
    markup=svg(w,h,body)
    source.write_text(markup,encoding='utf-8')
    target=ROOT/(name+'.png')
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes(resvg_py.svg_to_bytes(svg_string=markup,skip_system_fonts=True))

def background():
    body=f'<rect width="2560" height="1440" fill="{PAPER}"/>'
    # Chart grid and measured perimeter, designed to survive the native overlay.
    for x in range(0,2561,80): body+=line(x,0,x,1440,'#D7D9CA',1)
    for y in range(0,1441,80): body+=line(0,y,2560,y,'#D7D9CA',1)
    body+='<g opacity=".42">'
    for r in range(160,841,80): body+=circle(-40,580,r,'#7498A2',2)
    for x in range(-600,601,100):
        body+=f'<ellipse cx="-40" cy="580" rx="{abs(x)+50}" ry="640" fill="none" stroke="#7498A2" stroke-width="1.5"/>'
    for a in range(-70,71,10):
        t=math.radians(a)
        body+=line(-40,580,-40+1100*math.cos(t),580+1100*math.sin(t),'#7498A2',1)
    body+='</g>'
    body+='<g opacity=".32">'+compass(2280,1040,370)+'</g>'
    body+=f'<path d="M2560 0H2220L2560 340Z" fill="{BLUE}"/>'
    body+=f'<path d="M2560 0H2440L2560 120Z" fill="{RED}"/>'
    body+=f'<path d="M0 1440H440L0 1000Z" fill="{BLUE}"/>'
    # No top rule: Crop places it through the fixed account/version header.
    body+=line(96,1344,2464,1344,INK,2)
    for x in range(96,2465,24):
        body+=line(x,1344,x,1332 if x%96 else 1320,INK,1)
    for x,y in [(96,1344),(2464,1344)]:
        body+=circle(x,y,7,RED,2,PAPER)
    emit('background',2560,1440,body)

def title():
    body=compass(145,160,113)
    body+=word('PELAGIC',320,59,144,INK,75,7)
    body+=line(322,239,1420,239,RED,3)
    # The localized native tagline carries the subtitle. No illegible microtype.
    emit('title',1600,320,body)
    emit('icon',512,512,f'<rect width="512" height="512" fill="{PAPER}"/><rect x="18" y="18" width="476" height="476" fill="none" stroke="{INK}" stroke-width="3"/>'+compass(256,240,176)+word('ATLAS',155,446,26,INK,9,4))

def card(slot,idx,base,accent,motif):
    # 3:1 native-safe tickets. Quiet center; artwork remains decorative only.
    w,h=960,320
    body=f'<rect width="{w}" height="{h}" fill="{base}"/>'
    body+=f'<rect x="0" y="0" width="10" height="320" fill="{accent}"/>'
    body+=line(0,2,w,2,accent,4)+line(0,318,w,318,accent,4)
    body+=line(22,20,938,20,accent,1)+line(22,300,938,300,accent,1)
    body+='<g opacity=".16">'
    if motif==0:
        for r in [45,85,125,165,205]: body+=circle(870,160,r,accent,2)
        body+=line(645,160,1080,160,accent,2)+line(870,-60,870,380,accent,2)
    elif motif==1:
        for x in range(700,1100,32): body+=line(x,0,x-240,320,accent,2)
    else:
        for y in range(52,300,32):
            body+=f'<path d="M620 {y} Q680 {y-30} 740 {y}T860 {y}T980 {y}" fill="none" stroke="{accent}" stroke-width="2"/>'
    body+='</g>'
    # Serial bars and perforation stay at top/bottom, outside native label rows.
    for j in range(idx+1): body+=f'<rect x="{32+j*12}" y="284" width="5" height="8" fill="{accent}"/>'
    for y in range(30,300,18): body+=line(911,y,911,y+7,accent,1,extra='opacity=".32"')
    body+=f'<path d="M934 0L960 26V0Z M934 320L960 294V320Z" fill="{accent}"/>'
    emit('buttons/'+slot,w,h,body)

def package():
    for name in ['theme.toml','strings_zh.toml','strings_en.toml']:
        tomllib.loads((ROOT/name).read_text(encoding='utf-8'))
    (ROOT/'LICENSE').write_bytes((REPO/'LICENSE').read_bytes())
    paths=[ROOT/name for name in ['theme.toml','strings_zh.toml','strings_en.toml','README.md','ASSET-LICENSE.md','LICENSE','background.png','title.png','icon.png']]
    paths+=sorted((ROOT/'buttons').glob('*.png'))
    paths+=sorted((ROOT/'room').rglob('*.png'))
    # Every declared room image must exist; no unused room images ship by accident.
    theme=tomllib.loads((ROOT/'theme.toml').read_text(encoding='utf-8'))
    def image_paths(node):
        if isinstance(node,dict):
            for value in node.values(): yield from image_paths(value)
        elif isinstance(node,str) and node.startswith('room/') and node.endswith('.png'):
            yield node
    declared=set(image_paths(theme['room']))
    supplied={p.relative_to(ROOT).as_posix() for p in (ROOT/'room').rglob('*.png')}
    assert declared == supplied, f'Room image mismatch: {declared ^ supplied}'
    for path in paths:
        if path.suffix=='.png':
            with Image.open(path) as im: im.verify()
    out=REPO/'build/artifacts/theme-showcase/pelagic-atlas.rwtheme'
    out.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for path in sorted(paths):
            info=zipfile.ZipInfo(path.relative_to(ROOT).as_posix(),date_time=(2026,11,11,0,0,0))
            info.compress_type=zipfile.ZIP_DEFLATED
            info.external_attr=0o100644<<16
            z.writestr(info,path.read_bytes())
    digest=hashlib.sha256(out.read_bytes()).hexdigest()
    out.with_suffix('.rwtheme.sha256').write_text(f'{digest}  {out.name}\n',encoding='ascii')
    # Keep the existing Chinese download name in sync with the canonical archive.
    alias=out.with_name('远洋图志.rwtheme')
    alias.write_bytes(out.read_bytes())
    alias.with_suffix('.rwtheme.sha256').write_text(f'{digest}  {alias.name}\n',encoding='utf-8')
    print(f'{out}\nSHA-256 {digest}\n{out.stat().st_size:,} bytes; {len(paths)} files')

if __name__=='__main__':
    background()
    title()
    for idx,(slot,base,accent) in enumerate([
        ('singlePlayer','#F1D7C0',RED),('multiplayer','#CDDFE1',INK),('resources','#F5E9C5','#8D6A30'),
        ('mods','#FCF8EE',INK),('resourceBrowser','#FCF8EE',INK),('esports','#FCF8EE',RED),
        ('settings','#E8E9DF',INK),('openSourceInfo','#E8E9DF',INK),('extensions','#E8E9DF',INK),
    ]): card(slot,idx,base,accent,idx%3)
    build_room(emit,line,circle,compass)
    package()
