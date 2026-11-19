# LegacyGrim — возможности форка

Форк GrimAC для **Folia 1.21.11** с поддержкой дополнительных модулей движения, добычи, строительства и кристального PvP. Возможности ниже относятся к текущим локальным исходникам.

[Подробные правила и сборка](README-FORK.md)

## Возможности

### Движение и полёт

| Возможность | Что разрешено и какие есть ограничения |
|---|---|
| **EntitySpeed** | Ускоренное движение на управляемом транспорте до **60 км/ч** по горизонтали. |
| **Strafe** | Разрешен Strafe. |
| **Step / ReverseStep** | Подъём и спуск на **1 блок** по умолчанию, настройка `step-height` — от 0 до 2 блоков. ReverseStep в паутине отключён. |
| **ElytraFly Control** | По умолчанию **100 км/ч по горизонтали**, **10 км/ч вверх/вниз**, подъём до **10 блоков от начала текущего полёта**. Лимиты скорости настраиваются в `elytra-control` |
| **Scaffold Tower Plain** | Разрешён вертикальный подъём с установкой блоков под собой. |
| **FastWeb** |
| **NoSlow** | Движение и спринт без замедления от использования предметов. |
| **Velocity** | Разрешена отмена отдачи от ударов и взрывов. |

Скорости указаны при **20 TPS**. Обычное ускорение лодки на льду, ракеты и пикирование на элитрах сохраняются.

### Добыча, строительство и PvP

| Возможность | Правило |
|---|---|
| **InstantRebreak / Silent-добыча** | Разрешены Silent-добыча и повторное мгновенное разрушение блоков мягче обсидиана, включая эндерсундук. Обсидиан и более твёрдые блоки нужно добывать заново. |
| **Packet Criticals** | Разрешены пакетные криты на месте. |
| **Лимит установки блоков** | По умолчанию **2 установки за серверный тик** на игрока, общий лимит для обеих рук. Настраивается в `BlockPlaceLimit`. |
| **Темп установки кристаллов** | По умолчанию **10 установок/с** когда держишь кристалл в руке, с SilentSwap — **8/с**. Настраивается в `CrystalPlaceLimit` и `CrystalSwapPlaceLimit`. (FasterCrystals поддерживается) |
| **Silent swap кристаллов** | По умолчанию установка основной рукой разрешена со следующего тика после смены активного слота. Для установки в тот же тик — `AllowCrystalSilentSwap: true`. |
| **Дальность удара по кристаллам** | До **4,5 блока**. Дальность ударов по игрокам и другим сущностям не меняется. |
| **Установка кристаллов без проверки поворота** | Проверка RotationPlace для кристаллов отключена. |

### Защита и совместимость

- **FakeFly:** Полет на элитрах с одетым нагрудником запатчен.
- **Защита от phase перлами:** блокируются броски вплотную в блоки. Настройка `PearlPhasePrevention`.
- **Выталкивание из блоков:** подъем до свободного места максимум на 3 блока. Настройка — `PushOutOfBlocks`. (сыпучие блоки не включены)
- **Действия внутри полных блоков:** запрещены использование предметов, установка блоков и кристаллов, взаимодействие с сущностями и еда. Движение, атаки и добыча доступны. (сыпучие блоки не включены)
- **Multitask:** доступны отдельные варианты установки, описанные [ниже](#multitask).

## Настройки форка

Значения по умолчанию в `plugins/GrimAC/config.yml`:

```yaml
BlockPlaceLimit: 2
AllowCrystalSilentSwap: false
CrystalPlaceLimit: 10
CrystalSwapPlaceLimit: 8
step-height: 1.0
PearlPhasePrevention: true
PushOutOfBlocks: true
elytra-control:
    enabled: true
    horizontal-speed-kmh: 100.0
    vertical-speed-kmh: 10.0
    max-rise-blocks: 10.0
```

После изменения используйте `/grim reload`. У лимитов блоков и темпа кристаллов `0` отключает соответствующий лимитер. `step-height: 0` оставляет ванильный шаг. Для Control `enabled: false` отключает дополнительный допуск; `vertical-speed-kmh: 0` оставляет только горизонтальный Control; `max-rise-blocks: -1` снимает предел подъёма, а `0` запрещает дополнительный подъём выше старта.

Предел подъёма Control действует на один полёт. Новый полёт или телепорт сбрасывает отсчёт высоты.

У Strafe, EntitySpeed, FastWeb, Scaffold Tower Plain, Packet Criticals, NoSlow и Velocity отдельных переключателей нет.

Подробные правила и команды сборки — в [README-FORK.md](README-FORK.md).


## Multitask

В основных ветках LegacyGrim встроенной реализации Multitask нет. Доступны три варианта:

- **Рефлексия в плагине:** ветки [folia-1.21.11-multitask](https://github.com/Upwqrd/LegacyGrim/tree/folia-1.21.11-multitask) и [folia-26.3-multitask](https://github.com/Upwqrd/LegacyGrim/tree/folia-26.3-multitask). Включите `FoodMultitask: true` в конфиге плагина и перезапустите сервер.
- **Форк ядра:** [Paper-multitask](https://github.com/Upwqrd/Paper-multitask), [Folia-multitask](https://github.com/Upwqrd/Folia-multitask) или [Canvas-multitask](https://github.com/Upwqrd/Canvas-multitask). Выберите ветку `<версия>-multitask` под свой сервер.
- **Java-агент:** [MultitaskAgent](https://github.com/Upwqrd/MultitaskAgent). Добавьте `-javaagent:multitask-agent.jar` перед `-jar` в команду запуска и перезапустите сервер.

Проверенная конфигурация LegacyGrim для форка ядра или Java-агента:

```yaml
reset-item-usage-on-item-update: false
reset-item-usage-on-attack: false
reset-item-usage-on-slot-change: false
reset-item-usage-on-item-use: false
```

Иначе LegacyGrim может сам прерывать использование предмета, даже когда ядро или агент сохраняют его.

<div align="center">
 <h1>GrimAC</h1>

 <div>
  <a href="https://github.com/GrimAnticheat/Grim/actions/workflows/gradle-publish.yml">
   <img alt="Workflow" src="https://img.shields.io/github/actions/workflow/status/GrimAnticheat/Grim/gradle-publish.yml?style=flat&logo=github"/>
  </a>&nbsp;&nbsp;
  <a href="https://modrinth.com/plugin/grimac">
   <img alt="Modrinth" src="https://img.shields.io/modrinth/v/LJNGWSvH?style=flat&label=version&logo=modrinth">
  </a>&nbsp;&nbsp;
  <a href="https://modrinth.com/plugin/grimac#download">
   <img alt="Downloads" src="https://img.shields.io/modrinth/dt/LJNGWSvH?style=flat&logo=modrinth&label=downloads&link=https%3A%2F%2Fmodrinth.com%2Fplugin%2Fgrimac%23download">
  </a>&nbsp;&nbsp;
  <a href="https://discord.grim.ac">
   <img alt="Discord" src="https://img.shields.io/discord/811396969670901800?style=flat&label=discord&logo=discord">
  </a>
 </div>
 <br>
</div>

GrimAC is an open source Minecraft anticheat designed to support the latest versions of Minecraft.
It currently supports Minecraft versions 1.8â€“26.3. Geyser players are fully exempt from the anticheat to prevent false positives.
This project is considered feature-complete for the 2.0 (open-source) branch. If you would like a bug fix or enhancement and cannot sponsor the work, pull requests are welcome.
A premium version is planned, which will offer additional subscription-based paid checks, such as heuristics.

## Downloads

- Latest updates:
  - **[Modrinth](https://modrinth.com/plugin/grimac)** *(recommended)*
  - GitHub
  artifacts: [Bukkit](https://nightly.link/GrimAnticheat/Grim/workflows/gradle-publish/2.0/grimac-bukkit.zip), [Fabric](https://nightly.link/GrimAnticheat/Grim/workflows/gradle-publish/2.0/grimac-fabric.zip) *(bleeding edge)*
- Major releases only:
  - ~~[Hangar](https://hangar.papermc.io/GrimAnticheat/GrimAnticheat)~~
  - ~~[SpigotMC](https://www.spigotmc.org/resources/grim-anticheat.99923/)~~

## Requirements & Installation

- Java 17 or higher. *For more details, see [Updating-to-Java-17](https://github.com/GrimAnticheat/Grim/wiki/Updating-to-Java-17).*
- A Spigot, Paper, Folia, or Fabric server environment. *For more details, see [Supported-environments](https://github.com/GrimAnticheat/Grim/wiki/Supported-environments).*

If you use a proxy such as Velocity or BungeeCord:
- If you use Geyser, Floodgate must be installed on the backend server (where Grim is) so Grim can access the Floodgate API.
- If you use ViaVersion, it must be installed on the backend server (where Grim is) ONLY.
  Grim does not support having ViaVersion installed on the proxy, even if it is also installed on the backend.

## Resources

- For documentation and examples visit the [Wiki](https://github.com/GrimAnticheat/Grim/wiki).
- For answers to commonly asked questions visit the [FAQ](https://github.com/GrimAnticheat/Grim/wiki/FAQ).
- For community support and project discussion join our [Discord](https://discord.grim.ac).

## Pull Requests

See [Contributing](CONTRIBUTING.md) for more information about contributing and what our guidelines
are.

## Developer Plugin API

Grim's plugin API allows you to integrate Grim into your own plugins. Visit
the [plugin API repository](https://github.com/GrimAnticheat/GrimAPI) for the source code and more
information.

## Compiling From Source

1. `git clone https://github.com/GrimAnticheat/Grim.git`
2. `cd Grim`
3. `./gradlew build`
4. The final jars will compile into the `<platform>/build/libs` folders

## Grim Supremacy

What makes Grim stand out against other anticheats?

### Movement Simulation Engine

* We have a 1:1 replication of the player's possible movements
    * This covers everything from basic walking, swimming, knockback, cobwebs, to bubble columns
    * It even covers riding entities from boats to pigs to striders
* Built upon covering edge cases to confirm accuracy
* 1.13+ clients on 1.13+ servers, 1.12- clients on 1.13+ servers, 1.13+ clients on 1.12- servers,
  and 1.12- clients on 1.12- servers are all supported regardless of the large technical changes
  between these versions.
* The order of collisions depends on the client version and is correct
* Accounts for minor bounding box differences between versions, for example:
    * Single glass panes will be a + shape for 1.7-1.8 players and * for 1.9+ players
    * 1.13+ clients on 1.8 servers see the + glass pane hitbox due to ViaVersion
    * Many other blocks have this extreme attention to detail.
    * Waterlogged blocks do not exist for 1.12 or below players
    * Blocks that do not exist in the client's version use ViaVersion's replacement block
    * Block data that cannot be translated to previous versions is replaced correctly
    * All vanilla collision boxes have been implemented

### Fully asynchronous and multithreaded design

* All movement checks and the overwhelming majority of listeners run on the Netty thread
* The anticheat can scale to many hundreds of players, if not more
* Thread safety is carefully thought out
* The next core allows for this design

### Full world replication

* The anticheat keeps a replica of the world for each player
* The replica is created by listening to chunk data packets, block places, and block changes
* On all versions, chunks are compressed to 16-64 kb per chunk using palettes
* Using this cache, the anticheat can safely access the world state
* Per player, the cache allows for multithreaded design
* Sending players fake blocks with packets is safe and does not lead to falses
* The world is recreated for each player to allow lag compensation
* Client sided blocks cause no issues with packet based blocks. Block glitching does not false the
  anticheat.

### Latency compensation

* World changes are queued until they reach the player
* This means breaking blocks under a player does not false the anticheat
* Everything from flying status to movement speed will be latency compensated

### Inventory compensation

* The player's inventory is tracked to prevent ghost blocks at high latency, and other errors

### Secure by design, not obscurity

* All systems are designed to be highly secure and mathematically impossible to bypass
* For example, the prediction engine knows all possible movements and cannot be bypassed
