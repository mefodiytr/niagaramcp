# niagaramcp — Knowledge file format

Формат YAML-файла, в котором niagaramcp хранит семантическую модель
станции, накопленную через AI walkthrough (см.
[01-concept.md](01-concept.md)).

## Расположение

Default path: `${niagara_user_home}/niagaramcp/knowledge.yaml`

Конфигурируется через property `knowledgeFilePath` на
`BMcpPlatformService`. Если файл не существует — создаётся пустым
при первом write-операции от walkthrough tool'ов.

## Top-level structure

```yaml
schema_version: 1
station:
  id: afimall-main
  name: "АФИМОЛЛ Сити, основная станция"
  description: "Управление инженерными системами ТЦ"
  niagara_version: "4.15.3"
  generated_at: "2026-05-09T15:30:00Z"
  generated_by: "ai-walkthrough"   # or "manual" or "haystack-import"
  last_updated_at: "2026-05-09T16:45:00Z"

spaces:        []   # см. §Spaces
equipment_types: []  # см. §Equipment Types
equipment:     []   # см. §Equipment
points:        []   # см. §Points
schedules:     []   # опционально, v0.4
```

`schema_version` — целое число. Текущая версия 1. Все будущие
breaking changes файла — bump version + migration.

## Spaces (пространства)

Иерархия физических пространств. Может быть деревом любой глубины.

```yaml
spaces:
  - id: building-main
    name: "Главное здание"
    aliases: ["здание", "main building"]
    type: building
    description: "Основной корпус ТЦ"
    parent: null

  - id: floor-1
    name: "1 этаж"
    aliases: ["первый этаж", "floor 1"]
    type: floor
    parent: building-main

  - id: parking
    name: "Паркинг"
    aliases: ["паркинг", "parking", "стоянка"]
    type: parking
    parent: building-main
    description: "3 уровня подземного паркинга"

  - id: parking-sector-e
    name: "Паркинг, сектор E"
    aliases: ["сектор E", "паркинг E", "parking sector E", "P-E"]
    type: zone
    parent: parking
    bounds:
      level: -1
      area_m2: 1200
```

Поля:

- `id` (string, required, unique) — стабильный идентификатор. Не
  должен меняться, иначе ломаются ссылки. Стиль: `kebab-case`.
- `name` (string, required) — каноническое имя для отображения.
- `aliases` (list of string) — все альтернативные имена, по которым
  пользователи могут спросить. AI пополняет этот список, услышав
  новые формулировки.
- `type` (string) — категория: `building`, `floor`, `parking`,
  `zone`, `room`, `outdoor`, `mechanical-room`. Открытый список.
- `parent` (string или null) — id родительского space. `null` для
  top-level.
- `description` (string, optional) — свободный текст для AI как
  context.
- `bounds` (object, optional) — физические границы (этаж, площадь,
  координаты), если применимо.

## Equipment Types (типы оборудования)

Шаблоны для типов оборудования. Описывают, что такое AHU, что
такое Chiller, какие у них общие slots. Переиспользуются между
объектами.

```yaml
equipment_types:
  - id: ahu
    name: "Air Handling Unit"
    aliases: ["AHU", "вентиляционная установка", "ВУ", "приточка"]
    description: "Центральная приточно-вытяжная установка"
    typical_points:
      - role: supply_air_temp
        kind: temperature
        slot_patterns: ["SAT", "SupplyTemp", "T_Supply"]
        required: true
      - role: return_air_temp
        kind: temperature
        slot_patterns: ["RAT", "ReturnTemp", "T_Return"]
        required: false
      - role: supply_fan_status
        kind: boolean
        slot_patterns: ["FanStatus", "FanOn", "F_Stat"]
        required: true
      - role: supply_fan_speed
        kind: percent
        slot_patterns: ["FanSpeed", "F_Speed"]
        required: false
      - role: cooling_demand
        kind: percent
        required: false

  - id: rooftop
    name: "Rooftop Unit"
    aliases: ["RTU", "руфтоп", "крышник"]
    extends: ahu              # наследует typical_points от ahu
    description: "AHU расположенный на крыше, обычно с
        интегрированным компрессором"
    typical_points:
      - role: compressor_1_status
        kind: boolean
        required: false
      - role: compressor_2_status
        kind: boolean
        required: false

  - id: chiller
    name: "Chiller"
    aliases: ["чиллер", "холодильная машина"]
    typical_points:
      - role: chilled_water_supply_temp
        kind: temperature
      - role: chilled_water_return_temp
        kind: temperature
      - role: capacity
        kind: percent
      - role: status
        kind: boolean
```

Поля:

- `id` (string, required, unique) — например `ahu`, `chiller`,
  `vav`. Lowercase.
- `name`, `aliases`, `description` — как у space.
- `extends` (string, optional) — id другого типа, от которого
  наследуем `typical_points`. Например, `rooftop extends ahu`.
- `typical_points` — список **ожидаемых** ролей точек. Используется
  AI'ем при walkthrough для разметки конкретных оборудований:
  "Я ищу supply_air_temp у этого AHU. Возможные кандидаты по
  pattern: `SAT`, `SupplyTemp`. Подходит slot `SAT_AI_03` — это он?"
  - `role` (string) — семантическая роль внутри equipment.
    Машинный идентификатор.
  - `kind` (string) — `temperature`, `pressure`, `flow`, `humidity`,
    `boolean`, `percent`, `enum`, `count`, `power`, `energy`.
  - `slot_patterns` (list, optional) — типичные имена слотов в
    Niagara, помогают auto-suggest при walkthrough.
  - `required` (bool, default false) — обязательно ли быть.

## Equipment (конкретные единицы)

Каждый AHU/Chiller/etc на станции:

```yaml
equipment:
  - id: ahu-pa-e-01
    name: "AHU Паркинг E-01"
    aliases: ["AHU 1.1", "руфтоп 1.1", "крышник 1.1", "PA-E-01"]
    type: rooftop
    space: parking-sector-e
    ord: "station:|slot:/Drivers/BACnet/Roof/AHU_1_1"
    description: "Обслуживает паркинг сектор E, восточная половина"
    points:
      supply_air_temp: "station:|slot:/Drivers/BACnet/Roof/AHU_1_1/SAT"
      return_air_temp: "station:|slot:/Drivers/BACnet/Roof/AHU_1_1/RAT"
      supply_fan_status: "station:|slot:/Drivers/BACnet/Roof/AHU_1_1/F_Stat"
      compressor_1_status: "station:|slot:/Drivers/BACnet/Roof/AHU_1_1/C1_Stat"
    schedule: "schedule-trade-floor"  # ref to schedule, optional
    notes:
      - "Compressor 2 был отключен на ремонт 2026-04-15"
      - "Setpoint supply_air_temp = 16°C летом, 19°C зимой"

  - id: chiller-main-01
    name: "Chiller главный 1"
    aliases: ["чиллер 1", "ЧМ-1", "CH-01"]
    type: chiller
    space: mechanical-room-1
    ord: "station:|slot:/Drivers/BACnet/Mech/CH_01"
    points:
      chilled_water_supply_temp: "station:|slot:/Drivers/BACnet/Mech/CH_01/CHWS_T"
      chilled_water_return_temp: "station:|slot:/Drivers/BACnet/Mech/CH_01/CHWR_T"
      capacity: "station:|slot:/Drivers/BACnet/Mech/CH_01/Cap"
      status: "station:|slot:/Drivers/BACnet/Mech/CH_01/Run"
```

Поля:

- `id` (required, unique) — стабильный, kebab-case.
- `name` (required) — каноническое.
- `aliases` (list) — все человеческие имена.
- `type` (string, required) — id из `equipment_types`.
- `space` (string или null) — id space где находится.
- `ord` (string, required) — Niagara ord корня этой единицы.
  **Это связь с реальной станцией**.
- `points` (map: role → ord) — конкретные ord'ы для каждой
  семантической роли. Роли определены в `equipment_types`. Не все
  optional роли обязательны к наличию.
- `schedule` (optional) — ссылка на schedule.
- `notes` (list of string, optional) — свободный текст, операторские
  заметки, исторические факты, особенности эксплуатации. Это AI
  может цитировать в ответах ("по заметке от 2026-04-15, compressor
  2 был отключен на ремонт").

## Points (отдельные точки вне equipment)

Не все точки принадлежат конкретному equipment. Stand-alone
sensor'ы (датчики температуры в зоне, СО2, occupancy) — их полезно
тоже сем

ьировать:

```yaml
points:
  - id: temp-pa-e-ambient-01
    name: "Температура воздуха паркинг E, точка 1"
    aliases: ["T-PA-E-01"]
    space: parking-sector-e
    ord: "station:|slot:/Drivers/BACnet/Sensors/T_PA_E_01"
    kind: temperature
    role_in_space: ambient_air_temperature
    notes: ["Установлен на восточной стене"]

  - id: co2-pa-e-01
    name: "CO2 паркинг E, центр"
    space: parking-sector-e
    ord: "station:|slot:/Drivers/BACnet/Sensors/CO2_PA_E_01"
    kind: ppm
    role_in_space: ambient_co2
```

## Schedules (опционально, v0.4)

```yaml
schedules:
  - id: schedule-trade-floor
    name: "Расписание торгового зала"
    ord: "station:|slot:/Drivers/Schedule/TradeFloor"
    description: "Пн-Сб 09:00-22:00, Вс 10:00-21:00"
```

## Reserved fields and conventions

- `_walkthrough` (на любом уровне) — метаданные walkthrough'а:
  когда добавлено, кем подтверждено. Не используется в логике.
  ```yaml
  _walkthrough:
    confirmed_at: "2026-05-09T15:30:00Z"
    confirmed_by: "operator"     # or "ai-inferred-pending-confirmation"
    inferred_from: "slot_pattern_match"
  ```
- Поля начинающиеся с `_` — служебные, могут быть проигнорированы
  загрузчиком.
- Все строки UTF-8.
- Языки в name/aliases/description — на каком удобно. Aliases
  обычно содержат варианты на нескольких языках.

## Validation rules

При загрузке knowledge.yaml сервер валидирует:

1. Все `id` уникальны в пределах своей секции.
2. Каждый `equipment.type` — существующий id в `equipment_types`.
3. Каждый `equipment.space` (если задан) — существующий id в
   `spaces`.
4. Каждый `space.parent` (если задан) — существующий id в `spaces`,
   нет циклов.
5. Каждый `equipment_types[].extends` (если задан) — существующий
   id, нет циклов.
6. Каждый `ord` — синтаксически валидный Niagara ord. **Не**
   проверяем live что ord резолвится — это runtime check на
   момент use.
7. `schema_version` поддерживается текущей версией модуля.

При невалидном файле — лог error, сервер стартует с пустым
knowledge.

## Migration

Если будущая версия модуля меняет format (новый `schema_version`),
сервер при загрузке старого файла:

1. Делает backup: `knowledge.yaml.bak.v1.2026-05-09`.
2. Применяет миграцию.
3. Сохраняет новую версию.
4. Логирует what was migrated.

## Пример минимального файла

```yaml
schema_version: 1
station:
  id: small-office
  name: "Малый офис"
  generated_at: "2026-05-09T10:00:00Z"

spaces:
  - id: office
    name: "Офис"
    type: building

equipment_types:
  - id: ahu
    name: "Air Handling Unit"
    aliases: ["AHU", "ВУ"]

equipment:
  - id: ahu-1
    name: "AHU 1"
    aliases: ["центральная приточка"]
    type: ahu
    space: office
    ord: "station:|slot:/Drivers/BACnet/AHU_1"
    points:
      supply_air_temp: "station:|slot:/Drivers/BACnet/AHU_1/SAT"
```

Этого достаточно чтобы AI ответил на "какая температура supply
у центральной приточки".
