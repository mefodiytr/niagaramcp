# niagaramcp — AI Walkthrough Workflow

Как оператор/инженер вместе с AI проходит станцию и наполняет
[knowledge.yaml](02-format.md). Цель — за 1-2 часа на средний
объект получить рабочий semantic layer.

## Принципы

1. **AI инициирует, оператор подтверждает.** AI смотрит структуру
   станции, формирует гипотезы, задаёт вопросы. Оператор отвечает
   "да/нет/уточнение". Никаких самостоятельных tagging без
   подтверждения.

2. **Структура сверху вниз.** Сначала пространства (зоны),
   затем equipment, затем точки. Каждый уровень даёт контекст
   следующему.

3. **Инкрементально.** Можно остановиться в любой момент,
   knowledge.yaml сохранён. Следующий walkthrough продолжит с
   того места.

4. **Patterns из других объектов.** AI спрашивает "На предыдущем
   объекте AHU обычно имели supply_temp в slot SAT — здесь так
   же?" Использует ранее накопленные знания.

5. **Сначала тиражируется, потом редактируется.** AI
   может одним вопросом разметить группу однотипных компонентов
   ("видишь 12 компонентов BAhuController, все они руфтопы?"),
   а уточнения по конкретным вносятся точечно.

## Phases of walkthrough

### Phase 1 — Station overview (5 минут)

AI:
- Считает количество компонентов по типам (`BAhuController` × 12,
  `BChiller` × 3, etc.).
- Перечисляет top-level folder'ы под `Drivers/`.
- Спрашивает: "Расскажи об объекте: что это, сколько этажей,
  какие основные зоны?"

Operator:
- "Это ТЦ АФИМОЛЛ Сити. 5 этажей торговых, 3 уровня паркинга,
  крыша. Зоны: торговая часть, паркинг, mechanical rooms, офисная
  часть."

AI создаёт `spaces`:
- building-main, floor-1..5, parking-l1..l3, roof,
  mechanical-rooms, office-area

AI просит aliases: "Как ты обычно называешь паркинг? Сектора есть?"

Operator: "Паркинг A, B, C. Сектора в каждом."

AI разбивает дальше: parking-a, parking-b, parking-c, и спрашивает
про секторы внутри.

### Phase 2 — Equipment types confirmation (10 минут)

AI:
- "Я вижу 12 компонентов типа `BAhuController` в `Drivers/BACnet/Roof`.
  Это AHU? Какого подтипа — обычные или руфтопы?"

Operator:
- "Руфтопы все 12."

AI создаёт `equipment_types: [{id: rooftop, ...}]` если ещё нет
(или подтверждает что есть `rooftop extends ahu`).

AI продолжает по другим типам:
- "В `Drivers/BACnet/Mech` 3 компонента `BChiller`. Это чиллеры?"
- "В `Drivers/Modbus/VAV` 800 компонентов с похожими slots. Это
  VAV-коробки?"

Каждый тип подтверждается, общее описание сохраняется в
`equipment_types`.

### Phase 3 — Equipment placement (20-40 минут, основная фаза)

AI берёт каждое equipment и спрашивает где оно:

AI:
- "AHU `Drivers/BACnet/Roof/AHU_1_1`. Где располагается? Какой
  паркинг/этаж?"

Operator:
- "Это руфтоп 1.1 на крыше, обслуживает паркинг A сектор E."

AI:
- "Понял. Алиасы — 'руфтоп 1.1', 'крышник 1.1'. Какие ещё имена
  ты бы использовал?"

Operator:
- "AHU 1.1, PA-A-E-01, RT-1-1."

AI создаёт equipment запись:
```yaml
- id: ahu-pa-a-e-01
  name: "AHU Паркинг A-E-01"
  aliases: ["руфтоп 1.1", "крышник 1.1", "AHU 1.1", "PA-A-E-01", "RT-1-1"]
  type: rooftop
  space: parking-a-sector-e
  ord: "station:|slot:/Drivers/BACnet/Roof/AHU_1_1"
```

AI идёт дальше:
- "AHU `Drivers/BACnet/Roof/AHU_1_2`. Это аналогичный руфтоп?
  Какой сектор?"

**Bulk shortcut** для serial naming: если AI видит pattern
(AHU_1_1, AHU_1_2, ..., AHU_1_12 и пользователь подтвердил
формат), он может предложить:

AI:
- "Я вижу AHU_1_1 ... AHU_1_12 идут подряд. Они все руфтопы на
  крыше? Если да, давай я создам шаблонный mapping: AHU_1_N →
  руфтоп 1.N → паркинг A → сектор по букве N (A=1, B=2, ...)?"

Operator:
- "Да, но AHU_1_3 обслуживает торговый зал, не паркинг."

AI создаёт массово, делает исключение для AHU_1_3, спрашивает:
- "Хорошо. AHU_1_3 в каком пространстве? Какой этаж торгового
  зала?"

Это резко ускоряет walkthrough.

### Phase 4 — Point semantic roles (30-60 минут)

Для каждого equipment AI смотрит его slots и matches с
`typical_points` из equipment_types:

AI:
- "У AHU `руфтоп 1.1` (`Drivers/BACnet/Roof/AHU_1_1`) я вижу
  slots: `SAT`, `RAT`, `F_Stat`, `F_Speed`, `C1_Stat`, `C2_Stat`,
  `Damper`, `OAT`, `RH`. По типу `rooftop` ожидаю:
  - supply_air_temp → вижу `SAT`, подтверждаешь?
  - return_air_temp → вижу `RAT`, подтверждаешь?
  - supply_fan_status → вижу `F_Stat`, подтверждаешь?
  - compressor_1_status → вижу `C1_Stat`, подтверждаешь?
  - compressor_2_status → вижу `C2_Stat`, подтверждаешь?

  Незатронутые slots — `Damper`, `OAT`, `RH`. Что они означают?"

Operator:
- "Да, всё подтверждаю. `Damper` — позиция заслонки наружного
  воздуха, `OAT` — outdoor air temp (датчик прямо на руфтопе),
  `RH` — влажность приточного воздуха."

AI обновляет `equipment.points` плюс расширяет `equipment_types[rooftop].typical_points`
новыми ролями (damper_position, outdoor_air_temp, supply_humidity)
для будущих руфтопов.

**Pattern application для одинаковых equipment:**

После того как один AHU размечен, AI применяет тот же mapping ко
всем остальным AHU того же типа:

AI:
- "Применяю тот же mapping для AHU_1_2 ... AHU_1_12. Slots
  совпадают на 11 из 11 — без отклонений. Один раз тебе нужно
  подтвердить?"

Operator:
- "Да, подтверждаю."

AI делает batch update.

### Phase 5 — Stand-alone sensors (10 минут)

Sensors которые не привязаны к конкретному equipment (датчик в
зоне, CO2, occupancy):

AI:
- "Я нашёл 24 датчика типа `BNumericPoint` под `Drivers/BACnet/Sensors`,
  не привязанные к AHU/Chiller. По имени похоже на температурные
  датчики помещений (`T_PA_E_01`, `T_PA_E_02`, ...). Они в
  паркинге A, сектор E? И что измеряют — воздух?"

Operator:
- "Да, ambient air temperature, по 4 на каждый сектор."

AI создаёт `points` записи с `kind: temperature`, `role_in_space:
ambient_air_temperature`.

### Phase 6 — Validation pass (5-10 минут)

AI проходит по knowledge.yaml и подсвечивает:

- **Equipment без space** — "5 AHU не имеют space. Хотим
  пропустить или дополнить?"
- **Spaces без equipment** — "Создан space `office-area`, но
  никакого equipment к нему не привязано. Это OK?"
- **Equipment без обязательных points** — "AHU `руфтоп 1.7` не
  имеет supply_air_temp (slot не найден). Это нормально?"
- **Inconsistencies** — "Ord `Drivers/BACnet/Roof/AHU_1_5/SAT`
  имеет facets `units=Pa`, что необычно для supply_air_temp.
  Проверь что это температура, а не давление."

Operator подтверждает или вносит поправки.

## Tools нужные на сервере для walkthrough

(Это будут реализованы в v0.3.0 — см. [04-roadmap.md](04-roadmap.md))

### Read tools (исследовательские)

- `getOverview()` — список верхнеуровневых folder'ов под Drivers,
  счёт компонентов по типам.
- `listChildren(ord, depth)` — уже есть.
- `inspectComponent(ord)` — детально посмотреть компонент: тип,
  все slots, facets, parent.
- `findComponentsByType(typeName)` — найти все компоненты данного
  Niagara type.
- `getSlots(ord)` — список slots компонента с типами и facets.

### Write tools (для walkthrough)

- `createSpace(id, name, aliases, type, parent?)`
- `updateSpace(id, fields)`
- `createEquipmentType(id, name, aliases, extends?, typical_points?)`
- `updateEquipmentType(id, fields)`
- `createEquipment(id, name, aliases, type, space, ord, points?)`
- `updateEquipment(id, fields)`
- `bulkCreateEquipment(items)` — для pattern application
- `assignPointToEquipment(equipmentId, role, ord)`
- `createStandalonePoint(id, name, space, ord, kind, role_in_space?)`
- `validateKnowledge()` — запустить валидацию, вернуть warnings

### Read tools для работы со знанием

- `getKnowledgeSummary()` — counts: spaces, equipment_types,
  equipment, points. Что walkthrough'ом размечено, что нет.
- `findUnmappedComponents()` — компоненты в станции, которых нет
  в knowledge.yaml — kandidaty для следующего walkthrough'а.
- `exportKnowledge(format)` — выгрузка yaml/json/csv.
- `importKnowledge(content, mode: merge|replace)` — загрузка извне
  (например, шаблон с другого объекта).

## MCP Prompts для walkthrough'а

Заранее сформированные prompt-шаблоны, которые AI клиент
показывает оператору как "запустить walkthrough":

1. **"New station bootstrap"** — полный walkthrough с нуля. Запускает
   фазы 1-6 последовательно.

2. **"Continue walkthrough"** — продолжить с момента где остановились.
   AI начинает с `findUnmappedComponents`.

3. **"Verify equipment types"** — проходит по `equipment_types` и
   спрашивает уточнения по typical_points.

4. **"Apply pattern from another station"** — даёт оператору указать
   другой knowledge.yaml как donor; AI пытается применить его
   patterns к текущей станции.

5. **"Quick alias add"** — оператор просто говорит "AHU 1.5 — это у
   нас называется крышник восточный", AI добавляет alias без
   полноценного walkthrough'а.

## Persistence и atomicity

- Каждое создание/обновление в knowledge.yaml — атомарная запись
  (write to temp + rename).
- Перед write создаётся backup с timestamp, последние N backup'ов
  ротируются.
- Каждое изменение логируется в `knowledge.audit.log` (append-only):
  кто, когда, что.
- Если walkthrough прервался посередине (browser closed) — нет
  потери, всё уже на диске.

## Time budget

| Размер объекта | Equipment count | Время walkthrough'а |
|---|---:|---|
| Малый | < 50 | 30-45 мин |
| Средний | 50-500 | 1-2 часа |
| Большой | 500-2000 | 3-5 часов (можно за несколько сессий) |
| Очень большой | 2000+ | 1-2 дня (обязательно несколько сессий + pattern application) |

Это гораздо меньше, чем построить ту же модель Excel-вручную или
через ad-hoc tagging — потому что AI делает всю browsing-работу,
оператор только подтверждает.

## Что улучшать со временем

- **Auto-detection of patterns** — AI без подсказки спрашивает
  "вижу serial naming, давай шаблонизируем?".
- **Cross-station learning** — чем больше станций размечено, тем
  лучше AI predicts типы и роли.
- **Diff walkthrough** — после изменений в станции (добавлен AHU,
  переименован slot) AI показывает diff и спрашивает что обновить
  в knowledge.
- **Visual mode** — для клиентов с графическим интерфейсом, AI
  показывает дерево станции с tag-цветами, оператор кликает.

Эти улучшения — потом. v0.3.0 даёт минимально работающий
text-based walkthrough.
