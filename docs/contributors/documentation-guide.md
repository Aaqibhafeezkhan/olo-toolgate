# Documentation contributions

Keep one maintained guide per topic and link it from the [documentation home](../README.md).

- Describe current behavior, prerequisites, commands, failure handling and limits.
  Check names/defaults against source, schemas and tests.
- Keep genuinely unfinished proposals and acceptance tasks only in `docs/plans/`.
  Remove implemented tasks; do not retain progress percentages, delivery diaries,
  used prompts, dated test inventories or superseded architecture copies.
- Review trust, cryptography, persistence and public-contract changes in an issue.
  Update the owning architecture/operator guide with the implementation.
- Consolidate duplicate pages and repair incoming links, release asset paths
  and documentation tests when moving or deleting files.
- Preserve license notices, generated release snapshots and immutable fixtures
  consumed by tooling. Git history retains earlier designs and release changes.
- Keep secrets out of examples. Use relative links and current executable commands.
- Record actual checks in PRs/CI artifacts; keep generated evidence under ignored
  build output. A local demo or structural check is not production qualification.
- Run `make docs` or its documented Python checks after changing documentation.

The root README has two demonstration slideshows: bot execution and device
operation. Put additional images/videos in [media documentation](../media/README.md).
Keep detailed performance and monitoring guidance in their dedicated guides.
