# Quickstart Python runtime notices

Runtime requirements are pinned in `requirements.txt`. Their distribution metadata
and full license files ship under
`/usr/share/licenses/olo-toolgate/quickstart-python/`.

Psycopg 3.3.6 is used unmodified under LGPL-3.0-only as a separately replaceable
Python package dynamically linking the distribution's libpq. Its full corresponding
source archive, verified against the pinned SHA-256, ships alongside its license.
Users may replace this library in their derived image. ToolGate source licensing
remains Apache-2.0. Native libpq notices remain under `/usr/share/doc/libpq5/`.

Typing-extensions 4.15.0 is PSF-2.0. The other packages retain their upstream
notices. Container SBOM and dependency/license scans include these runtime
dependencies.

The Quickstart image includes CNCF Distribution (registry), Apache-2.0,
from the digest-pinned official registry:3.0.0 image. Source and notices:
https://github.com/distribution/distribution/tree/v3.0.0 . The registry is used
only when the explicit local workflow-stack entrypoint is selected.
