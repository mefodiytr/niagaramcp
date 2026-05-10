# Sample knowledge files

Demonstrate the v0.3.0 schema (`docs/concepts/02-format.md`) with
realistic but synthetic data. Use them to:

- Try out queries (`findEquipment`, `findInSpace`, `findPoints`)
  without a real station's worth of walkthrough work
- Validate your AI-client's MCP integration end-to-end
- Use as a template — copy, edit, and rename for your own project

## Files

- **`mall-knowledge.yaml`** — 4-floor shopping mall topology with
  RTU/AHU/FCU/chiller/boiler/CO2 equipment kinds. ~14 equipment +
  3 standalone points. Russian aliases included.
- *more samples to be added later (office, factory, mixed-use)*

## How to load

### Via importKnowledge tool (recommended)

After deploying niagaramcp v0.3.0+, call the tool with the file
content as a string:

```
tools/call importKnowledge {
  "source": "inline",
  "mode": "replace",
  "content": "<paste full YAML here>"
}
```

For `mode`:
- `replace` overwrites any existing knowledge
- `merge` preserves existing entries and adds new ones (collisions
  on `id` are resolved in favor of the new entry)

### Via direct file copy

If you have access to the station's filesystem:

1. Stop the station (Workbench → Application Director → Stop)
2. Copy `mall-knowledge.yaml` to `${niagaraUserHome}/niagaramcp/knowledge.yaml`
3. Start the station
4. The KnowledgeStore loads it automatically

### Via Python smoke client

You can also pipe through the smoke client if you've extended it
for your testing:

```powershell
$content = Get-Content samples\mall-knowledge.yaml -Raw
# call your test script with the content
```

## Important

The `ord` values in these samples are **synthetic** — they reference
component paths that don't exist on any real station. After
loading, validation tools (`checkKnowledgeIntegrity` once available
in v0.3.1+) will report most refs as broken because the components
they point to don't exist in your station.

To test queries that would actually return data, replace the ords
with real ones from your station, OR create matching dummy
components in your station for evaluation.

## Schema reference

See `docs/concepts/02-format.md` for the full schema specification:

- `spaces` — hierarchical zones, with optional `parent`
- `equipment_types` — kinds of equipment with their point catalogs
  (supports `extends` for inheritance)
- `equipment` — concrete equipment instances mapped to ords
- `points` — standalone points not belonging to any equipment
- `aliases` — case-insensitive alternative names for search

## Contributing samples

If you build out a sample for an interesting building type
(industrial, hospital, datacenter, multi-tenant office, etc.),
PRs welcome. Keep ords synthetic to avoid leaking real station
structure.
