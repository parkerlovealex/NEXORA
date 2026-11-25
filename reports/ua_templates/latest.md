# NEXORA UA template collection report

- Status: proposal_ready
- Proposal version: 2026.11.25.4
- Device catalog version: 2026.11.25.7
- Apps in source catalog: 18
- Template records: 11
- Status counts: candidate=11
- Coverage cells: verified=0, pending=0, candidate=5, outdated=0, not-collected=31
- Rejected findings: 0

## Collection gaps
- useragent.in category file not retrieved in this run: browser fetch returned HTTP 429 and Python urllib could not resolve the host. The exact dataset filename must be discovered from index.json on the GitHub Actions collection runner.
- No raw useragent.in rows have been copied into findings; 108 is a source-reported category count, not 108 records collected by NEXORA.
- The supplied Python scripts generate randomized app versions, build IDs, FBRV/Instagram IDs, and some device fields; they remain candidate format evidence only.
- The current meta.apps version/build history must be matched exactly before any observed template can become verified.

The collector does not promote candidates or pending records to verified.
Only validate_ua_templates.py can report whether a manually reviewed verified record meets the evidence gate.
