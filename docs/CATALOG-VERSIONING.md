# Catalog versioning

The NEXORA catalog is one release made of three artifacts, published from
`web/` (the same files the UA Studio site serves):

| Artifact | File | Version fields |
| --- | --- | --- |
| Devices | `web/devices.json` | `meta.dataVersion`, `meta.schemaVersion` |
| UA templates | `web/ua_templates.json` | `dataVersion`, `uaTemplatesSchema` |
| Desktop | `web/desktop-catalog.json` | `dataVersion`, `schemaVersion` |

## Two levels of versioning

- **`dataVersion`** identifies one artifact's own revision, in the form
  `YYYY.MM.DD.N`. Each artifact keeps its own history. Existing numbers are
  never renumbered to force the artifacts to match — the devices catalog is
  at `2026.11.25.7` and stays that way until its content next changes.
- **`catalogReleaseId`** identifies one complete, consistent catalog
  release, in the same `YYYY.MM.DD.N` form. Every artifact in the release
  carries the same id (in `meta` for devices, at top level for the other
  two), and `web/catalog-manifest.json` records the release as a whole.

`releaseSequence` in the manifest is a plain increasing integer (1, 2, 3…)
across releases. It exists so consumers can refuse an older release than
the one they already hold (anti-rollback, enforced once signing lands in
Phase 4.4).

## The manifest

`web/catalog-manifest.json` is generated, never hand-edited:

```sh
python3 tools/catalog_manifest.py generate   # rebuild from the artifacts
python3 tools/catalog_manifest.py check      # validate; exit 0 = all good
```

It records, per artifact: `dataVersion`, `schemaVersion`, `file`, `sha256`
and `sizeBytes`, computed from the artifact bytes. Generation is
deterministic — sorted keys, no wall-clock timestamps — so the same input
always produces the same manifest, and `check` fails if the committed
manifest differs from a fresh rebuild. The formal contract is
`catalog/manifest.schema.json`; `tools/catalog_manifest.py` enforces the
same rules plus the cross-artifact ones a schema cannot express:

- all three artifacts carry the same `catalogReleaseId`;
- `ua_templates.json`'s `deviceCatalogVersion` equals the devices
  `dataVersion` it was built against;
- every manifest hash and size matches the file on disk.

## Rules for a new release

1. Change artifact content as needed; bump that artifact's `dataVersion`
   (date of change + next free `.N` for that date). Never edit a published
   `dataVersion`'s content in place.
2. Mint one `catalogReleaseId` for the release (release date + next free
   `.N`) and set it in all three artifacts.
3. Increment `releaseSequence` in the generator when the release process is
   automated; for now it is set in `tools/catalog_manifest.py`.
4. Run `generate`, then `check`. Commit the artifacts and the manifest
   together.

## Trust status

The manifest is **unsigned**. Phase 4.4 adds a dedicated catalog signing
key (separate from the Tauri updater key), SHA-256 verification and the
signature block the manifest's `signing` object reserves. Until
`signing.status` is `signed`, no consumer may treat the manifest as a
trust anchor — it is a release contract and an integrity checklist only.

## Notes

- `data/desktop-platforms-2026-11-25.json` remains the as-collected desktop
  snapshot. `web/desktop-catalog.json` is the published artifact; from
  release `2026.11.28.1` it carries the release metadata (`dataVersion`,
  `catalogReleaseId`) that the snapshot does not.
- The Android payload is unchanged in shape: `devices.json` gained one
  additive `meta` field (`catalogReleaseId`). Its `schemaVersion` (2),
  `dataVersion` and every record are untouched.
