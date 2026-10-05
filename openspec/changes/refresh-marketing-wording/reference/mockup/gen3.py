import os
OUT = os.path.join(os.path.dirname(__file__), "styles.html")

# ---- a small "photo library": painterly gradient scenes, each a <symbol> 100x100 ----
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

def phone(kind, frame="#1d2026", rim="#3a3f48", screen_bg="#000", w=200, h=410, light_ui=False, glow="#2fd69b", newest=True, emph=None, hero="sunset", lib=None, thumb="boat", island=True):
    lib = lib or LIB
    """kind = camera | gallery. Realistic-ish iPhone-like frame, returned as an inline SVG."""
    u = []
    u.append(f'<svg viewBox="0 0 {w} {h}" xmlns="http://www.w3.org/2000/svg" class="ph" overflow="visible">')
    u.append(f'<defs><linearGradient id="rim{kind}{frame[1:]}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{rim}"/><stop offset=".5" stop-color="{frame}"/><stop offset="1" stop-color="{rim}"/></linearGradient>'
             f'<clipPath id="scr{kind}{frame[1:]}"><rect x="9" y="9" width="{w-18}" height="{h-18}" rx="30"/></clipPath></defs>')
    u.append(f'<rect x="-2.5" y="92" width="3" height="28" rx="1.5" fill="{rim}"/><rect x="-2.5" y="130" width="3" height="44" rx="1.5" fill="{rim}"/><rect x="{w-0.5}" y="120" width="3" height="60" rx="1.5" fill="{rim}"/>')
    u.append(f'<rect x="0" y="0" width="{w}" height="{h}" rx="38" fill="url(#rim{kind}{frame[1:]})"/>')
    u.append(f'<rect x="4" y="4" width="{w-8}" height="{h-8}" rx="35" fill="{frame}"/>')
    u.append(f'<g clip-path="url(#scr{kind}{frame[1:]})">')
    if kind == "camera":
        u.append(f'<rect x="9" y="9" width="{w-18}" height="{h-18}" fill="#000"/>')
        u.append(f'<use href="#{hero}" x="9" y="62" width="{w-18}" height="{(w-18)*4/3}"/>')
        cy = 62 + (w-18)*2/3
        u.append(f'<g stroke="#ffd60a" stroke-width="1.6" fill="none"><path d="M{w/2-26} {cy-18} v-8 h8 M{w/2+26} {cy-18} v-8 h-8 M{w/2-26} {cy+18} v8 h8 M{w/2+26} {cy+18} v8 h-8"/></g>')
        u.append(f'<circle cx="{w/2}" cy="{h-46}" r="23" fill="none" stroke="#fff" stroke-width="3.5"/><circle cx="{w/2}" cy="{h-46}" r="17.5" fill="#fff"/>')
        u.append(f'<rect x="28" y="{h-60}" width="28" height="28" rx="6" fill="#222"/><use href="#{thumb}" x="28" y="{h-60}" width="28" height="28"/>')
        u.append(f'<rect x="9" y="9" width="{w-18}" height="{h-18}" fill="#fff" opacity=".0" class="flash"/>')
    else:
        bg = "#ffffff" if light_ui else "#000"
        fg = "#111" if light_ui else "#fff"
        u.append(f'<rect x="9" y="9" width="{w-18}" height="{h-18}" fill="{bg}"/>')
        gap, cols = 2, 3
        t = (w - 18 - (cols-1)*gap) / cols
        items = lib[:]
        if newest: items = items + [hero]
        for i, sid in enumerate(items):
            r, c = divmod(i, cols)
            x, y = 9 + c*(t+gap), 48 + r*(t+gap)
            u.append(f'<use href="#{sid}" x="{x}" y="{y}" width="{t}" height="{t}"/>')
        pop = None
        if newest:
            i = len(items) - 1; r, c = divmod(i, cols); x, y = 9 + c*(t+gap), 48 + r*(t+gap)
            if emph == "dimonly":
                u.append(f'<rect x="9" y="48" width="{w-18}" height="{5*(t+gap)}" fill="{bg}" opacity=".6"/>')
                u.append(f'<use href="#{hero}" x="{x}" y="{y}" width="{t}" height="{t}"/>')
                u.append(f'<rect x="{x+1.5}" y="{y+1.5}" width="{t-3}" height="{t-3}" fill="none" stroke="{glow}" stroke-width="3"/>')
            elif emph:
                if emph == "dim":
                    u.append(f'<rect x="9" y="48" width="{w-18}" height="{5*(t+gap)}" fill="{bg}" opacity=".55"/>')
                pop = (x, y, t)
            else:
                u.append(f'<rect x="{x+1.5}" y="{y+1.5}" width="{t-3}" height="{t-3}" fill="none" stroke="{glow}" stroke-width="3"/>')
        # tab bar
        u.append(f'<rect x="40" y="{h-52}" width="{w-80}" height="30" rx="15" fill="{"#f1f1f3" if light_ui else "#1c1c1e"}"/>')
        u.append(f'<rect x="44" y="{h-48}" width="{(w-88)/2}" height="22" rx="11" fill="{"#fff" if light_ui else "#3a3a3c"}"/>')
    u.append('</g>')
    if island: u.append(f'<rect x="{w/2-30}" y="18" width="60" height="17" rx="8.5" fill="#000"/>')
    if kind == "gallery" and newest and emph and emph != "dimonly":
        x, y, t0 = pop; s = t0 * 1.55; cx, cy = x + t0/2, y + t0/2
        px, py = min(cx - s/2, w - s + 14), cy - s/2
        u.append(f'<defs><filter id="pg{emph}" x="-50%" y="-50%" width="200%" height="200%"><feDropShadow dx="0" dy="6" stdDeviation="7" flood-color="#000" flood-opacity=".35"/><feDropShadow dx="0" dy="0" stdDeviation="9" flood-color="{glow}" flood-opacity=".9"/></filter></defs>')
        u.append(f'<g filter="url(#pg{emph})"><rect x="{px-4}" y="{py-4}" width="{s+8}" height="{s+8}" rx="8" fill="#fff"/></g>')
        u.append(f'<svg x="{px}" y="{py}" width="{s}" height="{s}" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice"><use href="#{hero}" width="100" height="100"/></svg>')
        u.append(f'<rect x="{px-4}" y="{py-4}" width="{s+8}" height="{s+8}" rx="8" fill="none" stroke="{glow}" stroke-width="3"/>')
        u.append(f'<g><rect x="{px+s-36}" y="{py-16}" width="46" height="22" rx="11" fill="{glow}"/><text x="{px+s-13}" y="{py-0.5}" text-anchor="middle" font-size="11.5" font-weight="800" fill="#fff">New</text></g>')
    u.append('</svg>')
    return "".join(u)

def photo_card(cls="", hero="sunset"):
    return f'<svg viewBox="0 0 120 92" class="card {cls}"><rect width="120" height="92" rx="7" fill="#fff"/><svg x="5" y="5" width="110" height="82" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice"><use href="#{hero}" width="100" height="100"/></svg></svg>'

HEAD = "Your group’s photos,<br>in everyone’s library."
SUB = "Scan once. No account. No uploading."


