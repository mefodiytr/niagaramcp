# niagaramcp v0.3.0 — Semantic Layer Roadmap

Релиз делает niagaramcp полезным для естественно-языковых вопросов
по BMS-домену. Содержательно — три слоя поверх v0.2.0:

1. **Knowledge layer** — YAML файл с семантической моделью станции.
2. **Walkthrough tools** — для AI-based bootstrap'а этого файла.
3. **MCP Resources** — экспорт knowledge в URI'ish форму, чтобы
   AI клиенты могли загружать структуру в контекст.

Плюс необходимый minimum для типичных BMS-запросов:

4. **History tools** — чтение BHistoryExt.
5. **Alarms tools** — текущие и historical alarms.

## Scope

### In scope (v0.3.0)

**Knowledge layer:**
- YAML file format per [02-format.md](02-format.md), schema_version 1.
- Default location `${niagara_user_home}/niagaramcp/knowledge.yaml`,
  configurable.
- Auto-create on first write, backup on each modification.
- Validation on load с понятными error messages.
- Audit log file для всех изменений.

**Walkthrough tools** (Read):
- `getOverview()` → top-level station structure summary
- `inspectComponent(ord)` → детали компонента
- `findComponentsByType(typeName)` → поиск по Niagara type
- `getSlots(ord)` → все slots компонента с facets

**Walkthrough tools** (Write knowledge):
- `createSpace`, `updateSpace`
- `createEquipmentType`, `updateEquipmentType`
- `createEquipment`, `updateEquipment`, `bulkCreateEquipment`
- `assignPointToEquipment`
- `createStandalonePoint`
- `validateKnowledge`

**Knowledge management tools:**
- `getKnowledgeSummary` — counts и status
- `findUnmappedComponents`
- `exportKnowledge(format)` — yaml/json
- `importKnowledge(content, mode)` — merge/replace

**MCP Resources:**
- `niagara://overview` — generated из knowledge.station +
  equipment_types + counts (always loaded)
- `niagara://kinds/catalog` — equipment_types полностью (always loaded)
- `niagara://equipment/{id}` — конкретное equipment (template)
- `niagara://spaces/{id}` — пространство и его содержимое (template)
- `niagara://standalone-points/{id}` — отдельная точка (template)

**MCP Prompts:**
- `walkthrough.new_station` — phases 1-6 walkthrough
- `walkthrough.continue` — продолжить
- `walkthrough.verify_types` — проверить equipment_types
- `walkthrough.apply_pattern` — применить шаблон с другой станции
- `query.equipment_state` — состояние конкретного equipment
- `query.zone_comfort` — температуры/CO2/влажность в зоне
- `query.alarm_summary` — обзор alarms за период

**Search tools (использующие knowledge):**
- `findEquipment(query)` — search по name+aliases в `equipment`
- `findInSpace(spaceId, equipmentType?)` — что в этом space
- `findPoints(query)` — search по точкам через knowledge

**Niagara coverage:**
- `readHistory(ord, from, to, aggregation?)` — BHistoryExt access
- `getActiveAlarms(filter?)` — текущие alarms
- `getAlarmHistory(equipmentId или ord, from, to, filter?)` —
  историческая выборка

### Out of scope (откладывается)

- **OAuth2 resource server** — остаётся Bearer. Публичные
  deployments после v0.4.0.
- **Per-tool / per-ord RBAC** — все tools одинаково доступны при
  правильном Bearer token. После v0.4.0.
- **Schedules support** — read/write для BWeekSchedule. v0.4.0.
- **Programs execution** — `executeProgram(ord, args)`. v0.4.0+.
- **Server-initiated push messages** — degenerate GET /mcp
  остаётся. v0.5.0+ если будет realnaya потребность.
- **Streaming responses on POST** — POST всегда unary
  application/json. v0.5.0+ если history/bulk операции станут
  слишком долгими.
- **Project Haystack import/export** — отдельная фича. v0.4.0+.
- **Cross-station knowledge sharing** — MVP импорта/экспорта YAML
  есть, продвинутая sync — позже.
- **Visual walkthrough UI** — text-based достаточно для MVP.
- **Auto-pattern detection без явного вопроса** — AI всегда
  спрашивает подтверждение в v0.3.0. Позже можно научить
  "auto-apply confident patterns".

## Architecture impact

### New Java classes

`com.niagaramcp.server`:
- `KnowledgeStore` — load/save/validate YAML, atomic writes,
  backup rotation, audit log.
- `KnowledgeModel` — POJO иерархия (Station, Space,
  EquipmentType, Equipment, Point, Schedule).
- `KnowledgeYamlMapper` — YAML serialization (вероятно через
  снейкс-yaml; если хотим избежать новой зависимости — пишем
  свой минимальный YAML emitter под наш schema).
- `ResourceProvider` — generate niagara:// URIs из KnowledgeStore.

`com.niagaramcp.server.tools` — новые tools (см. scope выше).

`com.niagaramcp.server.history`:
- `HistoryReader` — wrapper над BHistoryExt API для readHistory.

`com.niagaramcp.server.alarms`:
- `AlarmsReader` — wrapper над BAlarmService.

### Dependencies

**Решение:** не добавляем snake-yaml как dependency, пишем
минимальный YAML reader/writer под наш ограниченный schema. Это
~300 LOC, но избегаем добавления библиотеки и сохраняем
"no new dependencies" политику. Если schema усложнится — пересмотрим.

Альтернатива: использовать org.json (уже embedded) и хранить
knowledge как JSON. Минус: операторы привыкли к YAML, его проще
редактировать. Плюс: ноль нового кода для парсинга.

**TBD на момент implementation** — выбираем YAML или JSON. Мой
склон в YAML за UX оператора при ручной правке.

### Niagara API coverage

Новые API surfaces:
- `BHistoryExt` (`javax.baja.history.ext.BHistoryExt`) — для
  readHistory.
- `BAlarmService` (`javax.baja.alarm.BAlarmService`) — для alarms.
- `BAlarmRecord`, `BAlarmTransition` — alarm primitive types.
- Возможно `BIHistoryRecord`, `BHistoryRecord` для timestamped
  values.

Новых Niagara dependencies в gradle.kts: возможно `:alarm-rt`
(если BAlarmService там) и `:history-rt` (если BHistoryExt). Это
обычно уже доступно через transitive deps от `:control-rt`.

### Permissions

Текущие `NETWORK_COMMUNICATION` + `UNAUTHENTICATED_ACCESS`
достаточны. Не нужно ничего нового — knowledge файл живёт в
`niagara_user_home`, что доступно модулю по дефолту.

### Backward compat

Все v0.2.0 features (SSE, Streamable HTTP, существующие 5 tools)
остаются работать без изменений. Новые tools добавляются в
ToolRegistry, новые resources/prompts появляются в `initialize`
capabilities response. Старые клиенты, которые не используют
resources/prompts — работают как прежде.

Размер jar после v0.3.0: ожидаемо +30-50 KB к v0.2.0 baseline
(120 KB), итого ~150-170 KB. В рамках разумного.

## Implementation order (для будущего промта)

После recon (см. ниже) — atomic commits в feature branch
`v0.3.0-semantic-layer`:

1. **docs(adr): 0002 Semantic enrichment layer design** — формальная
   фиксация решений из этих 4 документов.
2. **feat: knowledge file format and KnowledgeStore** — load/save/
   validate, atomic writes, backup, audit log. Tests — пустой
   файл, минимальный файл, full file, invalid file.
3. **feat: walkthrough read tools** — getOverview,
   inspectComponent, findComponentsByType, getSlots.
4. **feat: walkthrough write tools (basic)** — createSpace,
   createEquipmentType, createEquipment.
5. **feat: walkthrough write tools (advanced)** — bulk*,
   assignPointToEquipment, createStandalonePoint, validateKnowledge.
6. **feat: knowledge management tools** — summary,
   findUnmapped, export, import.
7. **feat: search tools** — findEquipment, findInSpace, findPoints.
8. **feat: history tool** — readHistory через BHistoryExt.
9. **feat: alarms tools** — getActiveAlarms, getAlarmHistory.
10. **feat: MCP resources** — overview, kinds/catalog,
    equipment/{id}, spaces/{id}, standalone-points/{id}.
11. **feat: MCP prompts** — все 7 шаблонов.
12. **docs: v0.3.0 release notes + smoke test runbook updates**
    (curl примеры для walkthrough'а, examples запросов с использованием
    knowledge).

Это 12 коммитов, объёмно но каждый изолированный. По скорости
~2-3 недели работы при focused execution.

## Pre-requisites

Прежде чем писать промт для v0.3.0:

1. **v0.2.0 в проде, протестирован.** Не делаем v0.3.0 на
   неподтверждённой базе.
2. **Ответы на 5 вопросов про реальные данные на твоих объектах**
   (см. конец [01-concept.md](01-concept.md) и хвост обсуждения
   05.09.2026):
   - распределение размеров объектов (малые/средние/большие)
   - есть ли naming convention
   - есть ли Haystack tags
   - есть ли проектный реестр точек
   - кто конечный пользователь walkthrough'а
3. **Recon для v0.3.0** — отдельный read-only проход по Niagara
   API surfaces для BHistoryExt и BAlarmService. Чтобы знать,
   какие именно методы у нас есть в Niagara 4.15.3 и как их
   правильно дёргать. Аналог recon-2026-05-09 но focused на
   history+alarms+knowledge file storage paths.

## Open questions для последующего обсуждения

- **Один knowledge.yaml на станцию или с разделением по zones?**
  Если станция огромная (5000 equipment), один YAML может стать
  100 КБ+ что неудобно для git diff. Рассмотреть split: один
  global + по файлу на каждый top-level space.
- **Как handlить переименования в Niagara station?** Если ord
  изменился, knowledge не знает об этом, point lookup fails.
  Нужна "rebuild references" фича.
- **Multi-language knowledge.** aliases на русском, name на
  русском, но AI инструкции на английском. Стоит ли вводить
  явное language tagging?
- **Versioning knowledge file.** Как откатываться если walkthrough
  что-то испортил? Backup есть, но UX отката?
- **Walkthrough resumability.** Если оператор closed conversation
  посередине walkthrough'а, как AI знает на какой фазе
  остановились? Сохранять `_walkthrough_state` в knowledge file?

Эти вопросы решаются по ходу implementation, не блокеры для старта.

## Success criteria для v0.3.0

После релиза, на тестовом малом объекте (50 equipment):

1. Оператор за 30-45 минут проходит walkthrough от пустого
   knowledge.yaml до полного.
2. После walkthrough'а AI клиент (n8n / Claude Desktop) загружает
   spine resources, и AI на простой запрос "какая температура в
   зоне X" отвечает за 1 round-trip без brute force.
3. AI на запрос "состояние AHU Y за вчера" возвращает: текущие
   key values + history график + alarms за период.
4. Knowledge.yaml читается/редактируется в любом текстовом редакторе,
   изменения подхватываются после reload.
5. Сборка модуля Java 8 compatible, jar < 200 KB.
6. SSE+Streamable transports v0.2.0 работают unchanged.

Если все шесть выполняются — v0.3.0 готов к деплою на средний
объект.
