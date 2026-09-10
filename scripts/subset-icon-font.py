#!/usr/bin/env python3
"""Rebuild the subsetted Material Symbols font, and prove the result still renders.

The full font is ~3.98MB for roughly 3,000 icons. BiteSite draws 64. Subsetting to those,
pinning the variable axes the CSS never varies, and refusing layout closure brings it to
about 9KB — the single largest asset win in the product.

Run this whenever static/fonts/material-symbols.glyphs.txt changes. IconGlyphCoverageTest
fails the build if a template references an icon the manifest does not list, so the usual
sequence is: add the icon to the template, run the tests, add the name the failure reports
to the manifest, then run this.

    python3 -m venv /tmp/fontenv
    /tmp/fontenv/bin/pip install fonttools brotli uharfbuzz
    /tmp/fontenv/bin/python scripts/subset-icon-font.py \\
        <full-font.woff2> \\
        src/main/resources/static/fonts/material-symbols.glyphs.txt \\
        src/main/resources/static/fonts/material-symbols-outlined.woff2

The full font is not kept in the repo; download the current Material Symbols Outlined
variable woff2 from Google Fonts and pass it as the first argument.

THREE THINGS HERE ARE LOAD-BEARING, each found the hard way:

  * Turn layout closure OFF. This is the one that mattered most, and its absence is why
    the font shipped at 377KB for two weeks while every test stayed green. fontTools'
    closure walks GSUB from the glyphs you asked for: hand it the letters that spell the
    icon names, leave closure on, and it dutifully re-adds every ligature those letters
    can possibly form — which is every icon in the font. The old code called
    populate(glyphs=..., text=...), and that `text` argument was the whole bug. The
    subset "succeeded", reported a glyph count nobody read, and wrote back a font with
    5,514 outlined glyphs in it.

  * Subset BEFORE instancing. The other order leaves gvar referencing glyph names the
    subsetter then removes, and fontTools raises KeyError.

  * Keep every layout feature. This font drives icons from rlig, and the ligature
    ordering inside it is what disambiguates a name that is a prefix of another
    ("check" against "check_circle", "person" against "person_add"). Do not prune to a
    hand-picked feature list.

Nothing above is trusted on reasoning alone: the script shapes all 64 names through
HarfBuzz at the end and refuses to write a font whose glyphs differ from the source's.
That check is the only thing here that would have caught the 377KB regression, because
the failure it guards is invisible to glyph counts and file sizes alike.

An icon is selected by ligature, so anything altering the element's text before shaping
breaks it — see the text-transform/letter-spacing rules on .material-symbols-outlined in
02-base.css.
"""

import os
import sys
import tempfile

from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools import subset

SRC, GLYPHS, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
names = [n for n in open(GLYPHS).read().split() if n]

source = TTFont(SRC)
axes = {a.axisTag for a in source['fvar'].axes} if 'fvar' in source else set()
print(f"  source: {os.path.getsize(SRC):,} bytes, "
      f"{len(source.getGlyphOrder()):,} glyphs, axes: {', '.join(sorted(axes)) or 'none'}")

# In this font each icon's glyph is NAMED after the icon, so they can be asked for
# directly. The letters that spell those names have to come too — they are the ligature's
# input sequence — and they are requested as glyphs rather than as `text` so that closure
# is never given an excuse to run.
present = set(source.getGlyphOrder())
cmap = source.getBestCmap()
missing = [n for n in names if n not in present]
if missing:
    sys.exit(f"  ERROR: not glyph names in this font: {', '.join(missing)}")
letters = {cmap[ord(c)] for n in names for c in n if ord(c) in cmap}
wanted = sorted(set(names) | letters)
print(f"  keeping {len(wanted)} glyphs ({len(names)} icons + {len(letters)} letters)")

opts = subset.Options()
opts.layout_features = ['*']   # rlig ordering disambiguates prefix names; keep it all
opts.layout_closure = False    # THE important line — see the docstring
opts.flavor = 'woff2'
opts.desubroutinize = False
opts.notdef_outline = True
opts.glyph_names = True        # keeps the shaping check below comparable by name

font = TTFont(SRC)
subsetter = subset.Subsetter(options=opts)
subsetter.populate(glyphs=wanted)
subsetter.subset(font)
print(f"  subset to {len(font.getGlyphOrder())} glyphs")

if axes:
    # Instancing AFTER subsetting: doing it first leaves gvar referencing glyph names the
    # subsetter then trims, and fontTools raises a KeyError on the mismatch.
    # FILL stays an axis (.is-filled varies it); everything else collapses to the single
    # value the CSS uses.
    pin = {t: v for t, v in {'wght': 400, 'GRAD': 0, 'opsz': 24}.items() if t in axes}
    if pin:
        font = instancer.instantiateVariableFont(font, pin, inplace=True, updateFontNames=False)
    left = [a.axisTag for a in font['fvar'].axes] if 'fvar' in font else []
    print(f"  pinned {pin or '{}'} -> axes remaining: {left or 'none'}")

tmp = os.path.join(tempfile.mkdtemp(), 'candidate.woff2')
font.flavor = 'woff2'
font.save(tmp)


def shaped(path):
    """Every icon name shaped through HarfBuzz, as glyph names. HarfBuzz cannot read
    woff2, so the font is decompressed to TTF first."""
    import uharfbuzz as hb
    f = TTFont(path)
    f.flavor = None
    ttf = os.path.join(tempfile.mkdtemp(), 'shape.ttf')
    f.save(ttf)
    order = f.getGlyphOrder()
    hbfont = hb.Font(hb.Face(hb.Blob.from_file_path(ttf)))
    out = {}
    for n in names:
        buf = hb.Buffer()
        buf.add_str(n)
        buf.guess_segment_properties()
        hb.shape(hbfont, buf)
        out[n] = [order[i.codepoint] for i in buf.glyph_infos]
    return out


try:
    before, after = shaped(SRC), shaped(tmp)
except ImportError:
    sys.exit("  ERROR: uharfbuzz is required to verify the subset. pip install uharfbuzz")

broken = []
for n in names:
    if len(after[n]) != 1:
        broken.append(f"{n}: did not collapse to one glyph -> {after[n]}")
    elif after[n] != before[n]:
        broken.append(f"{n}: {before[n]} became {after[n]}")

if broken:
    print(f"  REFUSING TO WRITE — {len(broken)} of {len(names)} icons would not render:")
    for b in broken:
        print("    ", b)
    sys.exit(1)

os.replace(tmp, OUT)
print(f"  shaping verified: {len(names)}/{len(names)} icons identical to the source font")
print(f"  wrote {OUT}: {os.path.getsize(OUT):,} bytes "
      f"({100 - os.path.getsize(OUT) * 100 // os.path.getsize(SRC)}% smaller than source)")
