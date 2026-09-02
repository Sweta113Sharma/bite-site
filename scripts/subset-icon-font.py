#!/usr/bin/env python3
"""Rebuild the subsetted Material Symbols font.

The full font is ~3.98MB for roughly 3,000 icons. BiteSite draws 63. Subsetting plus
pinning the variable axes the CSS never varies brings it to ~377KB — a 90% cut on the
single largest asset in the product.

Run this whenever static/fonts/material-symbols.glyphs.txt changes. IconGlyphCoverageTest
fails the build if a template references an icon the manifest does not list, so the usual
sequence is: add the icon to the template, run the tests, add the name the failure reports
to the manifest, then run this.

    python3 -m venv /tmp/fontenv && /tmp/fontenv/bin/pip install fonttools brotli
    /tmp/fontenv/bin/python scripts/subset-icon-font.py \\
        <full-font.woff2> \\
        src/main/resources/static/fonts/material-symbols.glyphs.txt \\
        src/main/resources/static/fonts/material-symbols-outlined.woff2

The full font is not kept in the repo; download the current Material Symbols Outlined
variable woff2 from Google Fonts and pass it as the first argument.

Two things here are load-bearing and were each found the hard way:

  * Subset BEFORE instancing. The other order leaves gvar referencing glyph names the
    subsetter then removes, and fontTools raises KeyError.
  * Keep every layout feature. This font drives icons from rclt as well as rlig; an
    attempt that kept only the usual ligature features silently broke exactly the icons
    whose name is a prefix of another ("logout" against "login"), which is what rclt
    disambiguates.

Note also that an icon is selected by ligature, so anything altering the element's text
before shaping breaks it — see the text-transform/letter-spacing rules on
.material-symbols-outlined in 02-base.css.
"""

import sys
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools import subset

SRC, GLYPHS, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(GLYPHS).read().split()

font = TTFont(SRC)
axes = {a.axisTag: (a.minValue, a.defaultValue, a.maxValue) for a in font['fvar'].axes} if 'fvar' in font else {}
print(f"  axes in source: {', '.join(axes) or 'none (static font)'}")

opts = subset.Options()
# Keep every layout feature. This font drives its icons from rclt AND rlig, and an
# earlier attempt that kept only the usual ligature features silently broke the icons
# whose name prefixes another ('logout' vs 'login'), which is exactly what rclt resolves.
opts.layout_features = ['*']
opts.flavor = 'woff2'
opts.desubroutinize = False
opts.notdef_outline = True
opts.glyph_names = True  # keep names so the retained set can be verified, here and later
# Ligature closure needs the letters that spell each icon name plus the ligature glyphs
# they resolve to; retaining the liga feature above is what keeps that mapping alive.
subsetter = subset.Subsetter(options=opts)
# Subsetting by text alone drops the icon glyphs: fontTools' ligature closure did not
# carry them across, and the result rendered every icon as its literal name. In this
# font each icon's glyph is *named* after the icon, so ask for them by name — and keep
# the letters too, since they are the ligature's input sequence.
present = set(font.getGlyphOrder())
wanted = [g for g in text if g in present]
missing = [g for g in text if g not in present]
if missing:
    print(f"  not glyph names in this font (ignored): {', '.join(missing)}")
subsetter.populate(glyphs=wanted, text=' '.join(text))
subsetter.subset(font)
print(f"  subset to {len(font.getGlyphOrder())} glyphs")

if axes:
    # Instancing AFTER subsetting: doing it first leaves gvar referencing glyph names the
    # subsetter then trims, and fontTools raises a KeyError on the mismatch.
    # FILL stays an axis; everything else collapses to the single value the CSS uses.
    pin = {t: v for t, v in {'wght': 400, 'GRAD': 0, 'opsz': 24}.items() if t in axes}
    font = instancer.instantiateVariableFont(font, pin, inplace=True, updateFontNames=False)
    left = [a.axisTag for a in font['fvar'].axes] if 'fvar' in font else []
    print(f"  pinned {pin} -> axes remaining: {left or 'none'}")

font.flavor = 'woff2'
font.save(OUT)
print(f"  wrote {OUT}")
