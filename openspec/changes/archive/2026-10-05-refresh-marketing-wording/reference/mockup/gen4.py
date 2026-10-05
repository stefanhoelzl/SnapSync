import os
D = os.path.dirname(os.path.abspath(__file__))
exec(open(os.path.join(D, "gen3.py")).read().split('HEAD = ')[0])  # scenes(), phone(), photo_card(), LIB

HEAD = "Every photo,<br>in your gallery."
SUB = "Just join an event, and your family and friends\' photos arrive in your gallery."

REAL_LIB = ["p129", "p164", "p177", "p342", "p1043", "p1080", "p217", "p28", "p450", "p65", "p1039"]
def real_symbols():
    s = ['<symbol id="rhero" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice"><image href="photos/hero1015.jpg" x="0" y="-16.7" width="100" height="133.3" preserveAspectRatio="xMidYMid slice"/></symbol>',
         '<symbol id="rhero_tall" viewBox="0 0 100 133.3" preserveAspectRatio="xMidYMid slice"><image href="photos/hero1015.jpg" width="100" height="133.3" preserveAspectRatio="xMidYMid slice"/></symbol>']
    for p in REAL_LIB + ["p436"]:
        s.append(f'<symbol id="r{p}" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice"><image href="photos/{p}.jpg" width="100" height="100" preserveAspectRatio="xMidYMid slice"/></symbol>')
    return "\n".join(s)

def slab(svg, w, h, depth, edge_front, edge_back, cls, rim_hl):
    """A phone with physical thickness: `depth` edge layers shading from front to back colour, a bright rim
    line on the front edge, and a glass glare on the face."""
    import colorsys
    def mix(a, b, f):
        a = [int(a[i:i+2], 16) for i in (1, 3, 5)]; b = [int(b[i:i+2], 16) for i in (1, 3, 5)]
        return "#" + "".join(f"{round(x + (y - x) * f):02x}" for x, y in zip(a, b))
    r = w * 0.19
    layers = "".join(f'<div class="edge" style="width:{w}px;height:{h}px;border-radius:{r}px;background:{mix(edge_front, edge_back, i/depth)};transform:translateZ({-i}px)"></div>' for i in range(1, depth + 1))
    return (f'<div class="slab {cls}" style="width:{w}px;height:{h}px">{layers}'
            f'<div class="face" style="width:{w}px">{svg}<div class="glare" style="border-radius:{r}px"></div>'
            f'<div class="rimhl" style="border-radius:{r}px;box-shadow:inset 0 0 0 1.5px {rim_hl}"></div></div></div>')

css = """
*{box-sizing:border-box}
body{margin:0;background:#6f757c;font-family:-apple-system,"SF Pro Display","Adwaita Sans","Cantarell",Roboto,Helvetica,Arial,sans-serif;padding:28px;color:#111}
.intro{max-width:1024px}.intro h1{margin:0 0 4px;font-size:26px}.intro p{margin:0 0 6px}
section{margin:30px 0}section h2{margin:0;font-size:19px}section p{margin:3px 0 10px;max-width:900px}
.banner{position:relative;width:1024px;height:500px;overflow:hidden;border-radius:12px;box-shadow:0 10px 40px rgba(0,0,0,.35);color:#fff;
  background:radial-gradient(70% 90% at 72% 55%,#1fa874 0%,#0b6a49 50%,#043523 100%);perspective:900px}
.copy{position:absolute;left:60px;top:50%;transform:translateY(-50%);width:440px;z-index:5}
.copy h3{margin:0;font-size:40px;line-height:1.08;letter-spacing:-.02em;font-weight:800}
.copy p{margin:18px 0 0;font-size:19px;opacity:.8}
.stage{position:absolute;left:500px;top:0;width:524px;height:500px;transform-style:preserve-3d}
.slab{position:absolute;transform-style:preserve-3d}
.slab .edge{position:absolute;left:0;top:0}
.slab .face{position:relative;transform:translateZ(2px)}
.slab .face svg{display:block;width:100%}
.glare{position:absolute;inset:0;pointer-events:none;background:linear-gradient(120deg,rgba(255,255,255,.22) 0%,rgba(255,255,255,0) 40%)}
.rimhl{position:absolute;inset:0;pointer-events:none}
.card{position:absolute;display:block;filter:drop-shadow(0 16px 22px rgba(0,25,15,.5))}
.shadow{position:absolute;top:448px;height:28px;border-radius:50%;background:radial-gradient(rgba(0,20,12,.7),rgba(0,20,12,0) 70%);filter:blur(5px)}
.glow{position:absolute;left:150px;top:130px;width:240px;height:240px;border-radius:50%;background:radial-gradient(#ffb35c55,#ffb35c00 70%);filter:blur(12px)}
/* two-phone layout */
.two .a{left:40px;top:50px;transform:rotateY(38deg) rotateX(5deg)}
.two .b{left:290px;top:50px;transform:rotateY(-38deg) rotateX(5deg)}
.two .card{width:112px;left:206px;top:190px;transform:translateZ(80px) rotate(-6deg)}
/* cloud layout */
.cl .a{left:16px;top:96px;transform:rotateY(38deg) rotateX(5deg)}
.cl .b{left:318px;top:96px;transform:rotateY(-38deg) rotateX(5deg)}
.cl .cloud{position:absolute;left:150px;top:22px;width:220px;transform:translateZ(40px)}
.cl .card{width:96px;left:212px;top:56px;transform:translateZ(60px) rotate(-5deg)}
.cl .arcs{position:absolute;left:0;top:0;width:524px;height:500px;transform:translateZ(30px)}
"""

DARK = dict(frame="#1d2026", rim="#4a505a")
LIGHT = dict(frame="#eef1f4", rim="#ffffff")
copy = f'<div class="copy"><h3>{HEAD}</h3><p>{SUB}</p></div>'

def phones(w, h, hero, lib, thumb):
    cam = slab(phone("camera", **DARK, hero=hero, thumb=thumb), w, h, 20, "#5a616c", "#16191e", "a", "rgba(255,255,255,.35)")
    gal = slab(phone("gallery", **LIGHT, light_ui=True, glow="#12b47c", emph="dimonly", hero=hero, lib=lib), w, h, 20, "#ffffff", "#9aa3ad", "b", "rgba(255,255,255,.9)")
    return cam, gal

def two(hero, lib, thumb):
    cam, gal = phones(190, 390, hero, lib, thumb)
    return (f'<div class="banner two">{copy}<div class="stage"><div class="glow"></div>'
            '<div class="shadow" style="left:40px;width:210px"></div><div class="shadow" style="left:290px;width:210px"></div>'
            + cam + gal + photo_card(hero=hero) + '</div></div>')

CLOUD = '''<svg class="cloud" viewBox="0 0 220 130"><defs><linearGradient id="cg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffffff"/><stop offset="1" stop-color="#d9efe6"/></linearGradient>
<filter id="cs" x="-30%" y="-30%" width="160%" height="160%"><feDropShadow dx="0" dy="10" stdDeviation="10" flood-color="#002a1a" flood-opacity=".45"/></filter></defs>
<path filter="url(#cs)" d="M52 118 C22 118 8 98 14 80 C20 62 40 56 54 60 C58 34 82 16 110 18 C138 20 156 38 160 58 C182 54 206 68 206 90 C206 108 192 118 172 118 Z" fill="url(#cg)"/></svg>'''
ARCS = '''<svg class="arcs" viewBox="0 0 524 500"><g fill="none" stroke="#fff" stroke-width="3" stroke-dasharray="2 9" stroke-linecap="round" opacity=".8">
<path d="M130 110 C 140 70, 160 60, 190 70"/><path d="M335 70 C 360 60, 385 70, 392 110"/></g></svg>'''

def cloud(hero, lib, thumb):
    cam, gal = phones(170, 349, hero, lib, thumb)
    return (f'<div class="banner cl">{copy}<div class="stage">'
            '<div class="shadow" style="left:0;width:190px;top:446px"></div><div class="shadow" style="left:352px;width:190px;top:446px"></div>'
            + cam + gal + ARCS + CLOUD + photo_card(hero=hero) + '</div></div>')

painted = ("sunset", LIB, "boat")
real = ("rhero", ["r" + p for p in REAL_LIB], "rp436")
secs = [
 ("1 · Two phones, painted", "Deeper phones (thick shaded edges, rim highlight, glass glare, ground shadow), camera labels removed.", two(*painted)),
 ("2 · Two phones, real photos", "Same with real photographs: a fjord on the camera and a library of trip and group pictures.", two(*real)),
 ("3 · Via the cloud, painted", "Camera → the photo resting on a cloud → the gallery. More honest about how it travels, but closer to 'upload to a platform', which the copy says SnapSync isn't.", cloud(*painted)),
 ("4 · Via the cloud, real photos", "The same with real photographs.", cloud(*real)),
]
html = f"""<!doctype html><meta charset="utf-8"><title>SnapSync — graphic refinements</title><style>{css}</style><body>
<svg width="0" height="0" style="position:absolute">{scenes()}{real_symbols()}</svg>
<div class="intro"><h1>Variant 3, refined</h1><p>Rest of the library dimmed, the new photo outlined, the card in flight. Real photos are stock placeholders from picsum.photos (Unsplash), for the look only.</p></div>
""" + "".join(f'<section><h2>{n}</h2><p>{d}</p>{b}</section>' for n, d, b in secs)
open(os.path.join(D, "refined.html"), "w").write(html)

# ---- real device frames (Pixel 9 Pro PNGs; screen box x49..1571, y63..3073 of 1620x3136) ----
K = 182 / 1280
def realphone(kind, png, cls, edge_front, edge_back, hero, lib, thumb, dw=200):
    sw = phone(kind, **(DARK if kind == "camera" else LIGHT), w=200, h=round(2856*K)+18, light_ui=True, glow="#12b47c",
               emph="dimonly" if kind == "gallery" else None, hero=hero, lib=lib, thumb=thumb, island=False)
    fw, fh = 1620*K, 3136*K
    inner = (f'<div style="position:relative;width:{fw}px;height:{fh}px">'
             f'<div style="position:absolute;left:{170*K}px;top:{142*K}px;width:182px;height:{2856*K}px;overflow:hidden;border-radius:22px"><div style="position:absolute;left:-9px;top:-9px;width:200px">{sw}</div></div>'
             f'<img src="frames/{png}" style="position:absolute;left:0;top:0;width:{fw}px;height:{fh}px">'
             f'<div class="glare" style="border-radius:{fw*0.12}px"></div></div>')
    def mix(a, b, f):
        a = [int(a[i:i+2], 16) for i in (1, 3, 5)]; b = [int(b[i:i+2], 16) for i in (1, 3, 5)]
        return "#" + "".join(f"{round(x + (y - x) * f):02x}" for x, y in zip(a, b))
    layers = "".join(f'<div class="edge" style="left:2px;top:2px;width:{fw-4}px;height:{fh-4}px;border-radius:{fw*0.13}px;background:{mix(edge_front, edge_back, i/16)};transform:translateZ({-i}px)"></div>' for i in range(1, 17))
    return f'<div class="slab {cls}" style="width:{fw}px;height:{fh}px">{layers}<div class="face" style="transform:translateZ(2px)">{inner}</div></div>'

def real_two():
    hero, lib, thumb = real
    cam = realphone("camera", "Pixel_9_Pro_-_Obsidian.png", "a", "#4b4f55", "#121417", hero, lib, thumb)
    gal = realphone("gallery", "Pixel_9_Pro_-_Hazel.png", "b", "#c9cbc4", "#7d8079", hero, lib, thumb)
    return (f'<div class="banner two">{copy}<div class="stage"><div class="glow"></div>'
            '<div class="shadow" style="left:40px;width:210px"></div><div class="shadow" style="left:290px;width:210px"></div>'
            + cam + gal + photo_card(hero=hero) + '</div></div>')

secs.insert(2, ("2b · Real photos, real phones", "Photographic device frames instead of drawn ones (here Pixel 9 Pro, since no iPhone frames are openly downloadable; the App Store version would use Apple's official iPhone frames from Apple Design Resources).", real_two()))
html = f"""<!doctype html><meta charset="utf-8"><title>SnapSync — graphic refinements</title><style>{css}</style><body>
<svg width="0" height="0" style="position:absolute">{scenes()}{real_symbols()}</svg>
<div class="intro"><h1>Variant 3, refined</h1><p>Rest of the library dimmed, the new photo outlined, the card in flight. Real photos are stock placeholders from picsum.photos (Unsplash), for the look only.</p></div>
""" + "".join(f'<section><h2>{n}</h2><p>{d}</p>{b}</section>' for n, d, b in secs[:3])
open(os.path.join(D, "refined.html"), "w").write(html)

# ---- flat, front-facing real frames: no fake 3D, only real shadows ----
def flatreal(kind, png, hero, lib, thumb, style):
    sw = phone(kind, **(DARK if kind == "camera" else LIGHT), w=200, h=round(2856*K)+18, light_ui=True, glow="#12b47c",
               emph="dimonly" if kind == "gallery" else None, hero=hero, lib=lib, thumb=thumb, island=False)
    fw, fh = 1620*K, 3136*K
    return (f'<div style="position:absolute;width:{fw}px;height:{fh}px;{style};filter:drop-shadow(0 2px 3px rgba(0,0,0,.35)) drop-shadow(0 30px 40px rgba(0,25,15,.55))">'
            f'<div style="position:absolute;left:{170*K}px;top:{142*K}px;width:182px;height:{2856*K}px;overflow:hidden;border-radius:22px"><div style="position:absolute;left:-9px;top:-9px;width:200px">{sw}</div></div>'
            f'<img src="frames/{png}" style="position:absolute;left:0;top:0;width:{fw}px;height:{fh}px"></div>')

def flat_banner(sep, scale=0.86, tilt=0):
    hero, lib, thumb = real
    a = flatreal("camera", "Pixel_9_Pro_-_Obsidian.png", hero, lib, thumb, f"left:0;top:0;transform:rotate({-tilt}deg)")
    b = flatreal("gallery", "Pixel_9_Pro_-_Hazel.png", hero, lib, thumb, f"left:{sep}px;top:0;transform:rotate({tilt}deg)")
    card = f'<div style="position:absolute;left:{sep/2+50}px;top:150px;width:118px;transform:rotate(-7deg);z-index:3">{photo_card(hero=hero)}</div>'
    return f'<div class="banner">{copy}<div style="position:absolute;left:{1024-40-(sep+230)*scale}px;top:{(500-446*scale)/2}px;transform:scale({scale});transform-origin:0 0;width:{sep+230}px;height:446px">{a}{b}{card}</div></div>'

secs2 = [("A · Front-facing, side by side", "The real frames as they are photographed: straight on, no fake 3D, only real shadows. This is how Apple's own frames are meant to be used.", flat_banner(250)),
         ("B · Front-facing, slightly turned toward each other", "Same frames, each rotated a few degrees in the picture plane: a little life, still no fake 3D.", flat_banner(270, tilt=5))]
html = f"""<!doctype html><meta charset="utf-8"><title>SnapSync — real frames</title><style>{css} .card{{position:relative;width:118px}}</style><body>
<svg width="0" height="0" style="position:absolute">{scenes()}{real_symbols()}</svg>
<div class="intro"><h1>Real frames, front-facing</h1><p>Pixel 9 Pro frames (the App Store version would use Apple's iPhone frames); photos are Unsplash placeholders.</p></div>
""" + "".join(f'<section><h2>{n}</h2><p>{d}</p>{b}</section>' for n, d, b in secs2)
open(os.path.join(D, "flat.html"), "w").write(html)
