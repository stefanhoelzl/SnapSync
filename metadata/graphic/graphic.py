"""SnapSync's use-case graphic: two phones on the brand green — one camera takes a photo, and it arrives in
the other phone's gallery (`metadata/messaging.md`; decision record: changes/archive/*-refresh-marketing-wording).

This module only builds HTML. `render.py` opens each layout in headless Chromium and writes the committed PNGs
(the look — perspective, depth, glow — needs a browser engine; a plain SVG rasteriser drops CSS 3D). Nothing
here runs in CI: the renders are committed like the screenshot raws, and `check.py` fails the build when they
no longer carry the tagline in `metadata/screenshots/en-US.json`.

The phones are deliberately GENERIC — a punch-hole camera, no Dynamic Island, no Apple button layout, no Apple
UI. Apple's Guidelines for Third Parties allow Apple hardware in listing images only as a photograph of the real
product, never as an artist's rendering (the same rule `.github/scripts/compose_screenshots.sh` cites for drawing
no device frame), and this graphic is the first App Store screenshot.
"""
import html

# The painted "photos": gradient scenes, each a <symbol> 100x100, so no photo licensing is involved.
def scenes():
    s = []
    s.append('''<symbol id="sunset" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice">
<defs><linearGradient id="g-sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#3b2a6b"/><stop offset=".45" stop-color="#e2557a"/><stop offset=".75" stop-color="#ffb35c"/></linearGradient>
<linearGradient id="g-lake" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#f29a6b"/><stop offset="1" stop-color="#3b2a6b"/></linearGradient>
<radialGradient id="g-sun"><stop offset="0" stop-color="#fff6d6"/><stop offset=".5" stop-color="#ffd27a"/><stop offset="1" stop-color="#ffd27a" stop-opacity="0"/></radialGradient></defs>
<rect width="100" height="100" fill="url(#g-sky)"/><circle cx="58" cy="58" r="22" fill="url(#g-sun)"/><circle cx="58" cy="58" r="7" fill="#fff3cf"/>
<path d="M0 60 L18 44 L30 52 L46 36 L62 54 L76 42 L100 58 V64 H0z" fill="#6b3a72" opacity=".8"/>
<path d="M0 64 L14 54 L28 60 L40 50 L58 62 L80 52 L100 62 V66 H0z" fill="#3e2557"/>
<rect y="66" width="100" height="34" fill="url(#g-lake)"/><rect x="54" y="68" width="8" height="2" fill="#ffe3a8" opacity=".8"/><rect x="51" y="73" width="14" height="1.5" fill="#ffe3a8" opacity=".5"/><rect x="55" y="78" width="6" height="1.2" fill="#ffe3a8" opacity=".4"/>
<path d="M8 100 L14 82 L20 100z M16 100 L22 76 L28 100z" fill="#24163a"/>
</symbol>''')
    s.append('''<symbol id="beach" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice"><defs><linearGradient id="g-b" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#7cc8f2"/><stop offset="1" stop-color="#d9f1ff"/></linearGradient></defs>
<rect width="100" height="100" fill="url(#g-b)"/><rect y="52" width="100" height="18" fill="#1fa2b8"/><rect y="56" width="100" height="3" fill="#7fe0e6" opacity=".6"/><path d="M0 68 Q50 62 100 70 V100 H0z" fill="#f2d6a2"/>
<path d="M60 40 Q72 26 84 40z" fill="#ff6b5b"/><path d="M72 40 V78" stroke="#7a5230" stroke-width="1.5"/></symbol>''')
    s.append('''<symbol id="forest" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice"><rect width="100" height="100" fill="#cfe7d6"/>
<path d="M-5 70 L10 30 L25 70z M15 72 L32 22 L49 72z M45 74 L60 36 L75 74z M70 72 L86 28 L102 72z" fill="#5e9c72"/>
<path d="M0 80 L18 44 L36 80z M30 84 L50 40 L70 84z M62 82 L82 48 L102 82z" fill="#2f6b49"/><rect y="80" width="100" height="20" fill="#24533a"/></symbol>''')
    s.append('''<symbol id="city" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice"><defs><linearGradient id="g-c" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0f1c3d"/><stop offset="1" stop-color="#3a2f6e"/></linearGradient></defs>
<rect width="100" height="100" fill="url(#g-c)"/><circle cx="78" cy="20" r="6" fill="#f4f1d0"/>
<path d="M0 100 V58 H14 V44 H26 V62 H36 V36 H50 V56 H60 V48 H74 V64 H86 V40 H100 V100z" fill="#141a33"/>
<g fill="#ffcf6b"><rect x="4" y="64" width="3" height="3"/><rect x="18" y="50" width="3" height="3"/><rect x="40" y="42" width="3" height="3"/><rect x="44" y="52" width="3" height="3"/><rect x="64" y="54" width="3" height="3"/><rect x="90" y="46" width="3" height="3"/><rect x="90" y="56" width="3" height="3"/><rect x="28" y="68" width="3" height="3"/></g></symbol>''')
    s.append('''<symbol id="snow" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice"><defs><linearGradient id="g-s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#4f8fd6"/><stop offset="1" stop-color="#bcdcf7"/></linearGradient></defs>
<rect width="100" height="100" fill="url(#g-s)"/><path d="M-5 80 L35 26 L58 56 L72 40 L105 80z" fill="#5b6f8f"/><path d="M35 26 L44 38 L38 36 L32 42 L28 36z M72 40 L78 48 L72 47 L67 50z" fill="#fff"/><rect y="78" width="100" height="22" fill="#eef5fb"/></symbol>''')
    s.append('''<symbol id="field" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice"><defs><linearGradient id="g-f" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffe2a8"/><stop offset="1" stop-color="#fff4dc"/></linearGradient></defs>
<rect width="100" height="100" fill="url(#g-f)"/><path d="M0 58 Q50 48 100 60 V100 H0z" fill="#e8b64a"/><path d="M0 72 Q50 62 100 74 V100 H0z" fill="#d49a2c"/><circle cx="24" cy="30" r="9" fill="#fff" opacity=".9"/>
<path d="M70 58 V40 M70 44 Q62 36 64 30 M70 44 Q78 36 76 30" stroke="#5a4a2a" stroke-width="2" fill="none"/><circle cx="70" cy="34" r="10" fill="#7aa35a"/></symbol>''')
    s.append('''<symbol id="people" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice"><defs><linearGradient id="g-p" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#ffb199"/><stop offset="1" stop-color="#ff7f8f"/></linearGradient></defs>
<rect width="100" height="100" fill="url(#g-p)"/><g fill="#5a2a3a"><circle cx="30" cy="48" r="9"/><path d="M14 100 Q14 62 30 62 Q46 62 46 100z"/><circle cx="62" cy="44" r="10"/><path d="M44 100 Q44 58 62 58 Q80 58 80 100z"/><circle cx="84" cy="56" r="7"/><path d="M72 100 Q72 68 84 68 Q96 68 96 100z"/></g></symbol>''')
    s.append('''<symbol id="cake" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice"><rect width="100" height="100" fill="#2d2440"/><circle cx="50" cy="50" r="40" fill="#ffcf6b" opacity=".18"/>
<rect x="26" y="56" width="48" height="26" rx="4" fill="#f7d9e3"/><rect x="26" y="56" width="48" height="7" rx="3" fill="#ff8fb1"/><g stroke="#9fd3ff" stroke-width="3"><path d="M40 56 V44 M50 56 V42 M60 56 V44"/></g><g fill="#ffcf6b"><circle cx="40" cy="41" r="2.5"/><circle cx="50" cy="39" r="2.5"/><circle cx="60" cy="41" r="2.5"/></g></symbol>''')
    s.append('''<symbol id="boat" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice"><rect width="100" height="100" fill="#cdeaf0"/><rect y="60" width="100" height="40" fill="#2f8fa8"/><path d="M30 62 H74 L66 72 H38z" fill="#fff"/><path d="M52 62 V28 L70 58z" fill="#fff"/><path d="M50 62 V32 L36 58z" fill="#ffd27a"/></symbol>''')
    return "\n".join(s)

LIB = ["beach", "forest", "city", "snow", "field", "people", "cake", "boat", "forest", "beach", "snow", "people", "field", "city"]
HERO = "sunset"
GREEN = "#12b47c"


def phone(kind, frame, rim, light_ui=False, w=200, h=410):
    """One generic phone as an inline SVG: `camera` (the viewfinder on the sunset, a shutter, the last-shot
    thumbnail) or `gallery` (a photo grid whose newest photo — the sunset — is outlined while the rest dims)."""
    uid = f"{kind}{frame[1:]}"
    u = [f'<svg viewBox="0 0 {w} {h}" xmlns="http://www.w3.org/2000/svg" overflow="visible">',
         f'<defs><linearGradient id="rim{uid}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{rim}"/>'
         f'<stop offset=".5" stop-color="{frame}"/><stop offset="1" stop-color="{rim}"/></linearGradient>'
         f'<clipPath id="scr{uid}"><rect x="9" y="9" width="{w-18}" height="{h-18}" rx="30"/></clipPath></defs>',
         # one plain button on each side — no Apple layout
         f'<rect x="-2.5" y="120" width="3" height="56" rx="1.5" fill="{rim}"/><rect x="{w-0.5}" y="140" width="3" height="40" rx="1.5" fill="{rim}"/>',
         f'<rect x="0" y="0" width="{w}" height="{h}" rx="38" fill="url(#rim{uid})"/>',
         f'<rect x="4" y="4" width="{w-8}" height="{h-8}" rx="35" fill="{frame}"/>',
         f'<g clip-path="url(#scr{uid})">']
    if kind == "camera":
        cy = 62 + (w - 18) * 2 / 3
        u += [f'<rect x="9" y="9" width="{w-18}" height="{h-18}" fill="#000"/>',
              f'<use href="#{HERO}" x="9" y="62" width="{w-18}" height="{(w-18)*4/3}"/>',
              f'<g stroke="#ffd60a" stroke-width="1.6" fill="none"><path d="M{w/2-26} {cy-18} v-8 h8 M{w/2+26} {cy-18} v-8 h-8 '
              f'M{w/2-26} {cy+18} v8 h8 M{w/2+26} {cy+18} v8 h-8"/></g>',
              f'<circle cx="{w/2}" cy="{h-46}" r="23" fill="none" stroke="#fff" stroke-width="3.5"/><circle cx="{w/2}" cy="{h-46}" r="17.5" fill="#fff"/>',
              f'<rect x="28" y="{h-60}" width="28" height="28" rx="6" fill="#222"/><use href="#boat" x="28" y="{h-60}" width="28" height="28"/>']
    else:
        bg = "#ffffff" if light_ui else "#000"
        gap, cols = 2, 3
        t = (w - 18 - (cols - 1) * gap) / cols
        items = LIB + [HERO]
        u.append(f'<rect x="9" y="9" width="{w-18}" height="{h-18}" fill="{bg}"/>')
        for i, sid in enumerate(items):
            r, c = divmod(i, cols)
            u.append(f'<use href="#{sid}" x="{9 + c*(t+gap)}" y="{48 + r*(t+gap)}" width="{t}" height="{t}"/>')
        r, c = divmod(len(items) - 1, cols)
        x, y = 9 + c * (t + gap), 48 + r * (t + gap)
        u += [f'<rect x="9" y="48" width="{w-18}" height="{5*(t+gap)}" fill="{bg}" opacity=".6"/>',
              f'<use href="#{HERO}" x="{x}" y="{y}" width="{t}" height="{t}"/>',
              f'<rect x="{x+1.5}" y="{y+1.5}" width="{t-3}" height="{t-3}" fill="none" stroke="{GREEN}" stroke-width="3"/>',
              f'<rect x="40" y="{h-52}" width="{w-80}" height="30" rx="15" fill="#f1f1f3"/>',
              f'<rect x="44" y="{h-48}" width="{(w-88)/2}" height="22" rx="11" fill="#fff"/>']
    u.append('</g>')
    u.append(f'<circle cx="{w/2}" cy="24" r="6" fill="#0a0a0a"/>')  # punch-hole camera, not a Dynamic Island
    u.append('</svg>')
    return "".join(u)


def photo_card():
    """The photo in flight between the phones."""
    return (f'<svg viewBox="0 0 120 92" class="card"><rect width="120" height="92" rx="7" fill="#fff"/>'
            f'<svg x="5" y="5" width="110" height="82" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice">'
            f'<use href="#{HERO}" width="100" height="100"/></svg></svg>')


def _mix(a, b, f):
    a = [int(a[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(b[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(x + (y - x) * f):02x}" for x, y in zip(a, b))


def slab(svg, w, h, depth, edge_front, edge_back, cls, rim_hl):
    """A phone with physical thickness: `depth` edge layers shading from front to back, a rim highlight, glare."""
    r = w * 0.19
    layers = "".join(f'<div class="edge" style="width:{w}px;height:{h}px;border-radius:{r}px;'
                     f'background:{_mix(edge_front, edge_back, i/depth)};transform:translateZ({-i}px)"></div>'
                     for i in range(1, depth + 1))
    return (f'<div class="slab {cls}" style="width:{w}px;height:{h}px">{layers}'
            f'<div class="face" style="width:{w}px">{svg}<div class="glare" style="border-radius:{r}px"></div>'
            f'<div class="rimhl" style="border-radius:{r}px;box-shadow:inset 0 0 0 1.5px {rim_hl}"></div></div></div>')


def stage():
    """The scene itself, 524x500: the dark camera phone and the light gallery phone turned toward each other."""
    cam = slab(phone("camera", "#1d2026", "#4a505a"), 190, 390, 20, "#5a616c", "#16191e", "a", "rgba(255,255,255,.35)")
    gal = slab(phone("gallery", "#eef1f4", "#ffffff", light_ui=True), 190, 390, 20, "#ffffff", "#9aa3ad", "b", "rgba(255,255,255,.9)")
    return ('<div class="stage"><div class="glow"></div>'
            '<div class="shadow" style="left:40px;width:210px"></div><div class="shadow" style="left:290px;width:210px"></div>'
            + cam + gal + photo_card() + '</div>')


CSS = """
*{box-sizing:border-box}
html,body{margin:0;padding:0;overflow:hidden;background:#043523}
body{font-family:"Adwaita Sans","Inter",-apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;color:#fff}
.canvas{position:relative;overflow:hidden;perspective:900px;background:radial-gradient(70% 90% at 72% 55%,#1fa874 0%,#0b6a49 50%,#043523 100%)}
.copy{position:absolute;z-index:5}
.copy h1{margin:0;font-weight:800;letter-spacing:-.02em;line-height:1.08}
.copy p{margin:18px 0 0;opacity:.8;line-height:1.35}
.stage{position:absolute;width:524px;height:500px;transform-style:preserve-3d}
.slab{position:absolute;transform-style:preserve-3d}
.slab .edge{position:absolute;left:0;top:0}
.slab .face{position:relative;transform:translateZ(2px)}
.slab .face svg{display:block;width:100%}
.glare{position:absolute;inset:0;background:linear-gradient(120deg,rgba(255,255,255,.22) 0%,rgba(255,255,255,0) 40%)}
.rimhl{position:absolute;inset:0}
.card{position:absolute;display:block;width:112px;left:206px;top:190px;filter:drop-shadow(0 16px 22px rgba(0,25,15,.5));transform:translateZ(80px) rotate(-6deg)}
.shadow{position:absolute;top:448px;height:28px;border-radius:50%;background:radial-gradient(rgba(0,20,12,.7),rgba(0,20,12,0) 70%);filter:blur(5px)}
.glow{position:absolute;left:150px;top:130px;width:240px;height:240px;border-radius:50%;background:radial-gradient(#ffb35c55,#ffb35c00 70%);filter:blur(12px)}
.a{left:40px;top:50px;transform:rotateY(38deg) rotateX(5deg)}
.b{left:290px;top:50px;transform:rotateY(-38deg) rotateX(5deg)}
"""

# Each layout: CSS-pixel size, the device scale the PNG is taken at, and where the copy and the stage sit.
# out = the committed file, relative to the repo root.
LAYOUTS = {
    # Google Play's feature graphic: exactly 1024x500.
    "feature-graphic": dict(w=1024, h=500, scale=1, out="metadata/play/images/featureGraphic.png", copy=True,
        copy_css="left:60px;top:50%;transform:translateY(-50%);width:440px", h1=40, p=19,
        stage_css="left:500px;top:0", bg="70% 90% at 72% 55%"),
    # The App Store's first screenshot: 440x956 x3 = exactly 1320x2868 (APP_IPHONE_69).
    "frame-appstore": dict(w=440, h=956, scale=3, out="metadata/graphic/frame-appstore.png", copy=True,
        copy_css="left:0;right:0;top:90px;text-align:center;padding:0 28px", h1=38, p=20,
        stage_css="left:12px;top:330px;transform:scale(.8);transform-origin:0 0", bg="80% 60% at 55% 55%"),
    # Google Play's first screenshot: 360x640 x3 = exactly 1080x1920 (9:16, as the compositor's Play canvas).
    "frame-play": dict(w=360, h=640, scale=3, out="metadata/graphic/frame-play.png", copy=True,
        copy_css="left:0;right:0;top:56px;text-align:center;padding:0 22px", h1=31, p=16,
        stage_css="left:2px;top:250px;transform:scale(.69);transform-origin:0 0", bg="80% 60% at 55% 60%"),
    # The landing page's hero: the Play feature graphic's composition WITHOUT its words, wider and lower, at 2x. The page sets the
    # same headline and support line over its left half as real text (selectable, translatable, read aloud).
    "site-hero": dict(w=1100, h=400, scale=2, out="metadata/graphic/site-hero.png", copy=False,
        stage_css="left:600px;top:0;transform:scale(.8);transform-origin:0 0", bg="70% 100% at 74% 55%"),
    # The store screenshots' background (`compose_screenshots.sh` lays each app capture on it), so every frame of a
    # store's set shares the graphic's green — the same gradient as the first frame, without its phones or words.
    "bg-appstore": dict(w=440, h=956, scale=3, out="metadata/graphic/bg-appstore.png", copy=False, stage=False,
        bg="80% 60% at 55% 55%"),
    "bg-play": dict(w=360, h=640, scale=3, out="metadata/graphic/bg-play.png", copy=False, stage=False,
        bg="80% 60% at 55% 60%"),
    # The same hero on a phone-width page: the phones alone, centred, on a TRANSPARENT background — the page
    # paints the green behind both the words and the picture, so there is no seam between them.
    "site-hero-narrow": dict(w=360, h=250, scale=3, out="metadata/graphic/site-hero-narrow.png", copy=False,
        stage_css="left:49px;top:0;transform:scale(.5);transform-origin:0 0", bg=None),
}


def page(layout, tagline):
    """The complete HTML document for one layout, sized exactly to its canvas."""
    L = LAYOUTS[layout]
    copy = ""
    if L["copy"]:
        head = html.escape(tagline["headline"]).replace(", ", ",<br>", 1)
        copy = (f'<div class="copy" style="{L["copy_css"]}"><h1 style="font-size:{L["h1"]}px">{head}</h1>'
                f'<p style="font-size:{L["p"]}px">{html.escape(tagline["support"])}</p></div>')
    bg = (f'background:radial-gradient({L["bg"]},#1fa874 0%,#0b6a49 50%,#043523 100%)' if L["bg"]
          else "background:transparent")
    page_bg = "" if L["bg"] else "<style>html,body{background:transparent}.glow{display:none}</style>"
    return (f'<!doctype html><meta charset="utf-8"><style>{CSS}</style>{page_bg}<body>'
            f'<svg width="0" height="0" style="position:absolute">{scenes()}</svg>'
            f'<div class="canvas" style="width:{L["w"]}px;height:{L["h"]}px;{bg}">{copy}'
            + (stage().replace('<div class="stage">', f'<div class="stage" style="{L["stage_css"]}">', 1)
               if L.get("stage", True) else "")
            + '</div>')
