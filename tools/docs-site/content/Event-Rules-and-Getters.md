# Event rules & getters

Talos has one shared catalog of **206 trigger names**. Every name works three ways:

- as a **rule**: `/talos on <name> … run <command>`
- as a **command getter**: `/talos get <name> [args]`
- as a **Python getter**: `talos.get("<name>", *args)`

Both surfaces call the same Java catalog, so they can never disagree on names or calculations. The getter
catalog is a strict **superset** — every trigger is a getter, plus some convenience getters with no
matching trigger. `/talos get list` and `/talos on list` dump everything live.

---

## Event rules — `/talos on … run …`

`/talos on <trigger> … run <command>` arms a persistent rule (saved to `~/.talos/rules.json`). `run` may
be prefixed with `chat ` to send a chat message instead of a command; commands dispatch through `/talos`
first, then fall back to the server. Placeholders `{value}`, `{health}`, `{x}`/`{y}`/`{z}` are substituted
from the firing event.

Grammar depends on the trigger's **kind**:

| Kind | Grammar |
|---|---|
| `NONE` | `on <trigger> run <cmd>` |
| `NUMBER` | `on <trigger> <value> run <cmd>` |
| `TEXT` | `on <trigger> [matching "<text>"] run` · also `count above <n> within <secs> run` |
| `COMPARE` | `on <trigger> [at <x> <y> <z> [radius <r>]] above\|below\|equals <n> [for <secs>] run` · also `changes above\|below\|equals <delta> within <secs> run` |
| `ENTITY_COUNT` | `on <trigger> <selector> radius <r\|-1> [at <x y z>] above\|below\|equals <n> run` |
| `ENTITY_PRESENCE` | `on <trigger> <selector> radius <r\|-1> [at <x y z>] run` |
| `BLOCK_COUNT` | `on <trigger> <block> radius <r> [at <x y z>] above\|below\|equals <n> run` |
| `BLOCK_PRESENCE` | `on <trigger> <block> radius <r> [at <x y z>] run` |
| `ITEM_COUNT` | `on <trigger> <item> above\|below\|equals <n> run` |
| `REGION` | `on <trigger> <x1 y1 z1 x2 y2 z2> run` |

The optional `at`/`radius` branch exists only for spatial metrics and the entity/block families;
non-spatial metrics reject it. Rule coordinates are resolved **when the rule is armed** and saved as exact
world coordinates, so a persistent `~`/`^` rule does not drift after creation.

```
/talos on health below 6 for 2 run chat low health, retreating
/talos on entity_count @e[type=zombie] radius 16 above 3 run chat too many zombies
/talos on chat matching "diamond" count above 3 within 10 run chat spam detected
/talos on health changes below -4 within 2 run chat taking burst damage
/talos on block_near minecraft:lava radius 5 run chat lava nearby
/talos on light_level at ~ ~1 ~ below 8 run chat it is dark above me
/talos on item_picked_up @e[type=player] matching diamond run chat someone grabbed a diamond
/talos on entered_region 100 60 100 120 80 120 run chat entered the build zone
/talos on tick_every 100 run /talos get health
```

Other rule commands: `/talos rules list`, `/talos rules remove <id>`, `/talos rules clear`,
`/talos every <secs> run <cmd>` (persists), `/talos after <secs> run <cmd>` (session only),
`/talos on list` (dumps every trigger's grammar).

Selectors support `@e`, `@a`, `@p`, `@s`, `@r`, `@n`, including bracket filters. Radius `-1` = the whole
loaded client world.

---

## Getters — `talos.get(name, *args)` / `/talos get name`

Numeric getters return `int`/`float`, booleans `bool`, descriptive/latest-event values `str`. In Python,
spaces normalize to underscores (`talos.get("server tps")` == `talos.get("server_tps")`); commands use
underscores. Spatial getters accept a coordinate (absolute, `~` feet-relative, or `^` local); a single
string works too: `talos.get("light_level", "~ ~1 ~")`.

### Getter families

| Family | Args | Returns |
|---|---|---|
| Live numeric metric (53) | none; spatial ones also take `x y z` | current number via the rule engine's calculator (`health`, `hunger`, `server_tps`, `speed`, `light_level`, `nearest_hostile_distance`, `yaw`, …) |
| `entity_count` / `entity_near` / `entity_gone` | `selector [radius=-1] [x y z]` | exact matching loaded-entity count |
| `block_count` / `block_near` | `block [radius=16] [x y z]` | exact matching block count in the centered cube |
| `item_count` / `hotbar_item_count` | `item` | exact inventory / hotbar count |
| `held_enchant` | `enchantment` | main-hand enchantment level, or `0` |
| `entered_region` / `left_region` | `x1 y1 z1 x2 y2 z2` | inside / outside the cuboid (bool) |
| Threshold triggers | none | underlying value (`health_below` → health, `air_below` → air, `xp_level_above` → XP, `tick_every` → tick) |
| Event getters | none | latest payload + `[N.NNs ago]`, or `never observed`; entity events also append runtime `entity_id`, UUID, type, 3dp pos |
| State/string getters | none | current boolean/string where one exists (`sneaking`, `standing_on`, `position`, `dimension`, `held_item`, …) |

### A few examples

```python
talos.get("server_tps")                          # float, client TPS estimate
talos.get("entity_count", "@e[tag=guard]", 48)   # int
talos.get("block_near", "minecraft:lava", 8)     # int count, not merely True/False
talos.get("held_enchant", "minecraft:efficiency")# int level
talos.get("villager_profession_changed")         # "villager label: old -> new" + id/UUID/pos
talos.get("entity_location", 123)                # "type#123 @ x.xxx y.yyy z.zzz", or -1
talos.get("entered_region", 100, 60, 100, 120, 80, 120)  # bool
```

### Trigger categories (all readable as getters)

- **Vitals & player state** — `health`, `hunger`, `air`, `xp_level`, `armor_points`, `speed`,
  `damage_taken`, `healed`, `death`, `respawn`, `on_fire`, `falling`, `sneaking`, `sprinting`,
  `jumped`, `landed`, `moving`, `teleported`, …
- **World** — `time_day`/`time_night`, `weather_rain`/`weather_clear`, `dimension_changed`,
  `biome_changed`, `chunk_changed`, `world_loaded`/`world_unloaded`, `explosion`, `light_level`, …
- **Entities & projectiles** — `entity_spawned`/`entity_removed`, `entity_hurt`/`entity_died`,
  `entity_armor_changed`, `villager_profession_changed`, `projectile_launched`, `pearl_thrown`,
  `projectile_hit`, `totem_popped`, `entity_status`, …
- **Items & inventory** — `item_gained`/`item_lost`, `item_picked_up`, `held_changed`, `tool_broken`,
  `inventory_full`, `slot_changed`, `container_opened`/`container_closed`, `container_item_gained`, …
- **Text, sound, packets** — `chat`, `actionbar`, `title`/`subtitle`, `mention`, `sound`,
  `particle_seen`, `packet_received`, `bossbar_shown`/`bossbar_updated`, `sidebar_score_changed`, …

The four generic catch-alls make everything else inspectable: `packet_received`, `entity_status`,
`particle_seen`, and `sound`. For the exhaustive list with per-trigger payloads, run `/talos get list`
in-game or read the [README catalog](https://github.com/sheepishlyroyal/talos/blob/main/README.md#talosget-and-trigger-return-value-catalog).

## Client-side ceilings

Some facts are simply not synced to a client and can never be observed: **villager inventories**, **closed
chest contents** (only knowable while the chest screen is open), and **beacon effects** (only via the
beacon screen). Equipment/profession/level for villagers *are* synced and covered by triggers.
