# Working in this repository

This is a standalone production example of using published Glyphora artifacts.
Use `./mill`; do not replace the build tool or resolve Glyphora from a sibling checkout.
Keep `tui-test` test-only. `scripts/verify-dependencies.py` verifies that boundary.

Preserve optional readings, units, alias precedence, freshness, and config fields owned by other clients.
Never add real device addresses, serial numbers, credentials, or captured home readings to fixtures or screenshots.
Demo mode must remain offline and must not touch shared config or persistent history.
Keep network and disk IO off Glyphora's render thread. Own and close every polling scope.

Before pushing: run tests, format checks, dependency checks, screenshot checks, and packaged smoke checks
appropriate to the change. UI changes need regenerated actual-renderer SVG/text captures.
Check a real controlling PTY when changing startup, bindings, input, or shutdown.
Report synthetic/mock validation separately from hardware validation.

Use a new version tag for releases; do not move an existing tag. Version is in `Main.Version`.
Keep the package script and release workflow consistent with it.
