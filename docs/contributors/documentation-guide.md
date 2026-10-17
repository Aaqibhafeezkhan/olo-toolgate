# Documentation contributions

Update documentation with the feature it describes. Keep one maintained guide per
topic and link to it from [the documentation home](../README.md) or its section index.

- Lead with the task, current behavior and prerequisites. Include commands that
  exist in this checkout, expected results and relevant failure/recovery behavior.
- Clearly distinguish supported behavior, design requirements and unfinished work.
  Record pending features in [the single backlog](../planning/README.md).
- Verify API fields, configuration and defaults against source, schemas and tests.
  Do not turn a planned integration into a supported-feature claim.
- Use safe example values and relative links. Keep security boundaries explicit.
- Delete or consolidate superseded pages and repair their incoming links.
  Do not commit used prompts, conversation notes, first-commit inventories or
  duplicate implementation plans. Git history retains past work.
- Keep ADRs as decision history and mark superseded decisions. Retain license
  notices, generated package documentation and reproducible compatibility evidence.
- Run `make docs` and check local links after moving or removing documentation.

Avoid copied file trees and test counts without a revision/environment. Describe
recorded evidence accurately; never present an old run as current verification.
