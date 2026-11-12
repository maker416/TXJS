# Copyright 2023-2025 RWPP contributors
# 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束。
# Use of this source code is governed by the GNU AGPLv3 license.
# https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
"""Original briefing-table artwork. Decoration never encodes player identity/state text."""

INK = '#183A49'
RED = '#B84732'

def rect(w, h, color):
    return f'<rect width="{w}" height="{h}" fill="{color}"/>'

def build_room(emit, line, circle, compass):
    # A separate plotting table: softly tinted paper, registration crosses and
    # offset plotting rings, not a reused menu background or baked interface.
    b = rect(2560, 1440, '#E6E4D8')
    for x in range(0, 2560, 64):
        b += line(x, 0, x, 1440, '#CCD4CA', 1)
    for y in range(0, 1440, 64):
        b += line(0, y, 2560, y, '#CCD4CA', 1)
    for x in range(128, 2560, 256):
        for y in range(128, 1440, 256):
            b += line(x-6,y,x+6,y,'#A9BDB4',1)+line(x,y-6,x,y+6,'#A9BDB4',1)
    b += '<g opacity=".20">'+compass(2440, 1250, 360)+'</g>'
    b += '<g opacity=".18">'
    for r in (260, 320, 380, 440, 500): b += circle(-100, 720, r, '#315D70', 3)
    b += '</g>'
    # Quiet perimeter only; no heading rules through native text.
    b += '<path d="M0 0H46V1440H0Z" fill="#C5D1C9"/>'
    b += '<path d="M2560 0H2514V1440H2560Z" fill="#C5D1C9"/>'
    emit('room/background', 2560, 1440, b)

    # Five individually composed materials, rather than one skin copied five times.
    for name, base in [('players','#F8F2E5'),('map','#E8EFF0'),('details','#F6EAD4'),('actions','#DDE6E1'),('chat','#F4F6EF')]:
        w,h=1200,600
        b=rect(w,h,base)
        if name=='players':
            for y in range(36,h,48): b+=line(0,y,w,y,'#DDDCCF',1)
            for x in (20,1180): b+=line(x,0,x,h,'#B7C9C6',2)
            for y in range(28,h,48):
                b+=circle(10,y,3,'#B7C9C6',1,'#FCF8EE')
        elif name=='map':
            for x in range(0,w,40): b+=line(x,0,x,h,'#D4E0DF',1)
            for y in range(0,h,40): b+=line(0,y,w,y,'#D4E0DF',1)
            b+='<g opacity=".16">'+compass(1080,520,160)+'</g>'
        elif name=='details':
            for x in range(0,w,96): b+=line(x,0,x,h,'#E8DBC0',1)
            for y in (8,592): b+=line(0,y,w,y,'#B49B6D',2)
            for x in range(0,w,20): b+=line(x,580,x,592,'#B49B6D',1)
        elif name=='actions':
            for x in range(-600,1800,28): b+=line(x,0,x+600,600,'#D4DFD9',1)
            b+=line(0,4,w,4,INK,5)+line(0,596,w,596,INK,5)
        else:
            for y in range(24,h,40): b+=line(0,y,w,y,'#DDE5DC',1)
            b+=line(24,0,24,h,'#D2B6A6',2)
            for x in range(48,w,96): b+=circle(x,588,2,'#A8BBB1',1,'#A8BBB1')
        emit('room/panels/'+name,w,h,b)

    # State images differ in color AND edge geometry. Never bake readiness labels,
    # names, flags, lock states or a fake positive checkmark into these tickets.
    row_specs=[('player','#FCFAF3','#B0BFB8'),('local','#E0EEF0','#315D70'),
        ('host','#F3E6C5','#947036'),('ai','#E4E8E5','#677A75'),
        ('ready','#E8F0E5','#557B61'),('waiting','#F4E8DE','#AD7958'),
        ('empty','#F3F3EB','#A2B0A5')]
    for kind,base,accent in row_specs:
        b=rect(1200,192,base)
        b+=line(0,2,1200,2,accent,3)
        b+=line(0,188,1200,188,accent,3)
        if kind=='local':
            b+=line(0,181,1200,181,accent,2)
            for x in range(24,1200,72): b+=line(x,8,x+28,8,accent,3)
        elif kind=='host':
            for x in range(0,1200,32):
                b+=f'<path d="M{x} 185l16 -9l16 9l-16 7Z" fill="{accent}" opacity=".55"/>'
        elif kind=='ai':
            for x in range(0,1200,24):
                b+=f'<rect x="{x}" y="178" width="9" height="7" fill="{accent}"/>'
        elif kind=='ready':
            b+=line(0,183,1200,183,accent,6)
        elif kind in ('waiting','empty'):
            for x in range(0,1200,30): b+=line(x,181,x+15,181,accent,3)
        else:
            for x in range(0,1200,12): b+=circle(x,181,1.2,accent,1,accent)
        # Watermark sits outside the name region; extremely low contrast when Crop
        # puts it under other columns. All core columns remain native content.
        b+='<g opacity=".07">'
        for r in (60,90,120): b+=circle(1110,96,r,accent,2)
        b+='</g>'
        # Wide roster rows are ~12:1. A 6:1 asset would vertically Crop away
        # its identifying seams on desktop; 12.5:1 preserves them in both modes.
        emit('room/players/'+kind,1200,96,'<g transform="scale(1 .5)">'+b+'</g>')

    # Passport stamp faces. The icon has bold geometry readable at 24dp.
    for state,base,accent in [('normal','#F4F1E5','#9CAB9E'),('lit','#D6E7DF','#487363'),('unavailable','#E9E7E0','#A6ABA1')]:
        b=rect(192,192,base)
        b+=f'<rect x="9" y="9" width="174" height="174" rx="9" fill="none" stroke="{accent}" stroke-width="3"/>'
        if state=='lit':
            for x,y,sx,sy in [(17,17,1,1),(175,17,-1,1),(17,175,1,-1),(175,175,-1,-1)]:
                b+=line(x,y,x+sx*23,y,accent,6)+line(x,y,x,y+sy*23,accent,6)
        elif state=='unavailable':
            for x in range(-180,380,24): b+=line(x,0,x+192,192,accent,1,extra='opacity=".22"')
        else:
            for x in range(24,180,18): b+=circle(x,172,2,accent,1,accent)
        emit('room/profile/'+state,192,192,b)
    icon=f'<rect x="15" y="23" width="130" height="114" rx="9" fill="none" stroke="{INK}" stroke-width="10"/>'
    icon+=circle(57,65,15,INK,0,INK)
    icon+=f'<path d="M33 111V102Q33 83 57 83Q81 83 81 102V111Z" fill="{INK}"/>'
    icon+=line(99,61,126,61,INK,8)+line(99,83,126,83,INK,8)+line(99,105,117,105,INK,8)
    emit('room/profile/icon',160,160,icon)

    # Independent command strips: warm command, blue communications, neutral
    # utilities. Central text stays clear at short, wide and square crops.
    button_specs=[('start','#B84732','#F8E7CF'),('options','#FCF8EE',INK),
        ('selectMap','#E4EEF0','#315D70'),('addAI','#E6EBE4','#526E61'),
        ('changeSite','#F2EAD8','#947036'),('invite','#D3E6E4','#315D70'),
        ('publish','#E8ECDC','#68794D'),('lockRoom','#F2E3D4','#9C6846'),
        ('memberInvites','#E8EAD9','#68794D'),('sendChat','#315D70','#F6EFDD')]
    for idx,(name,base,accent) in enumerate(button_specs):
        b=rect(640,192,base)
        b+=line(0,4,640,4,accent,3)+line(0,187,640,187,accent,4)
        # Keep decorative key marks at both edges; no icon claiming current state.
        for x in range(16,625,32):
            b+=line(x,178,x+10,178,accent,2,extra='opacity=".38"')
        if name=='start':
            b+=f'<path d="M0 32L26 58V134L0 160Z M640 32L614 58V134L640 160Z" fill="{accent}" opacity=".6"/>'
        else:
            b+=f'<path d="M0 0H28V28Z M640 192H612V164Z" fill="{accent}" opacity=".65"/>'
        emit('room/buttons/'+name,640,192,b)
