#!/usr/bin/env python3
"""Concatenate css/parts/*.css into the single stylesheet the pages actually link.

    python3 scripts/build-css-bundle.py

WHY A BUNDLE AT ALL
Every page linked all nine parts, and every one of them is render-blocking: the browser
cannot paint until the last has arrived. The app origin serves HTTP/1.1, which caps a
browser at roughly six connections and gives each its own TLS handshake, so on a campus
connection those nine arrive in waves. One file is one request.

It is also smaller. gzip compresses the concatenation against a single shared dictionary
rather than restarting for each file, which is worth about 13% here — 69,159 bytes as nine
separate responses, 59,994 as one.

WHY THIS IS SAFE, WHICH IS NOT OBVIOUS
The header on every part says the load order IS the cascade: a rule may only override one
in an earlier file. Concatenating in that same order preserves it exactly, because a
stylesheet's cascade does not care whether two rules arrived in one file or two. That
holds only while three things stay true of the parts, all checked before this was written:

  * no `@import` — it is only legal at the top of a stylesheet, so concatenation would
    silently drop every one after the first part
  * no `@charset` — same rule, same failure
  * no RELATIVE `url()` — those resolve against the stylesheet's own URL, which changes
    when the file moves from /css/parts/ to /css/

If you add any of those to a part, this bundle stops being equivalent and CssBundleTest is
not clever enough to notice. Everything else is fair game.

The parts remain the editable source. This output is generated, and CssBundleTest fails the
build if it drifts from them, so a part edited without regenerating cannot ship.
"""

import io
import os

PARTS = ['01-tokens', '02-base', '03-customer', '04-auth', '05-shared',
         '06-editorial', '07-brutalist', '08-customer-final', '09-console']

HEADER = ("/* GENERATED FILE — DO NOT EDIT.\n"
          "   Concatenation of css/parts/*.css in cascade order, built by\n"
          "   scripts/build-css-bundle.py and pinned by CssBundleTest.\n"
          "   Edit the parts; regenerate this. */\n")

base = 'src/main/resources/static/css/parts'
out = 'src/main/resources/static/css/app-bundle.css'

chunks = []
for name in PARTS:
    body = io.open(f'{base}/{name}.css', encoding='utf-8').read()
    chunks.append(f"/* ==== {name}.css ==== */\n{body.rstrip()}\n")

io.open(out, 'w', encoding='utf-8').write(HEADER + "\n".join(chunks))
print(f"  wrote {out}: {os.path.getsize(out):,} bytes from {len(PARTS)} parts")
