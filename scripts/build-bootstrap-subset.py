#!/usr/bin/env python3
"""Cut Bootstrap down to the rules this app actually uses.

    python3 scripts/build-bootstrap-subset.py

Reads   css/vendor/bootstrap-5.3.3.full.css   (the vendored original, never linked)
Writes  css/vendor/bootstrap.min.css          (what the pages actually load)

WHY
Bootstrap ships ~232KB of CSS to deliver the 172 classes this app uses. On the campus
connections this product runs on that is 22KB of gzip nobody needs. The subset is about
9KB gzipped against 31KB for the whole thing.

WHY THE FULL COPY STAYS IN THE REPO
So this stays regenerable. If the served file were the only copy, the first template to
use a Bootstrap class that had already been purged would find it missing, and there would
be no original left to rebuild from. The full file is never linked by any page; it exists
only as the input here.

WHAT MAKES THIS SAFE
Purging by content scan is only as good as its idea of "used", and the usual way it goes
wrong is a class name built at runtime that no scanner can see. This app has two:
queue-poll.js picks a button class and a badge class from lookup tables. Both are plain
string literals in that file, so scanning the JS as well as the templates catches them --
which is why --content includes static/js and not just the templates.

BootstrapSubsetTest asserts that every Bootstrap class referenced anywhere in the app is
still defined in the output, and fails the build if one is not. That test is the contract;
this script is just what satisfies it.

Requires node/npx (purgecss runs from npm, no install needed).
"""

import subprocess
import os
import sys

VENDOR = 'src/main/resources/static/css/vendor'
SRC = f'{VENDOR}/bootstrap-5.3.3.full.css'
OUT = f'{VENDOR}/bootstrap.min.css'

# Bootstrap state classes that JS can add at runtime and a content scan may not see.
# Cheap to keep, expensive to discover missing.
SAFELIST = """show fade active disabled collapsing collapse is-invalid is-valid was-validated
invalid-feedback valid-feedback modal-open modal-backdrop offcanvas-backdrop
dropdown-menu-end visually-hidden""".split()

cmd = [
    'npx', '--yes', 'purgecss@6',
    '--css', SRC,
    '--content',
    'src/main/resources/templates/**/*.html',
    'src/main/resources/static/js/*.js',
    'src/main/resources/static/*.html',
    '--variables',
    '--safelist', *SAFELIST,
    '--output', OUT,
]

before = os.path.getsize(SRC)
print(f"  source: {before:,} bytes")
res = subprocess.run(cmd)
if res.returncode != 0:
    sys.exit("  purgecss failed")

after = os.path.getsize(OUT)
print(f"  wrote {OUT}: {after:,} bytes ({100 - after * 100 // before}% smaller)")
print("  now run: mvn -Dtest=BootstrapSubsetTest test")
