# BigEnderChest

Doubles the ender chest from 27 to 54 slots on Paper 26.2. Nothing else.

- No commands.
- The big chest opens only by right-clicking an ender chest block.
- Slots 1-27 are the normal vanilla ender chest, so existing items are kept. Slots 28-54 are extra slots stored in `plugins/BigEnderChest/data/`.
- `/ec`, `/echest`, `/enderchest` etc. (for example from EssentialsX) are blocked by default. Turn this off in `config.yml` with `block-enderchest-commands: false`.

## Requirements
Paper 26.2, Java 25.

## Build
```
mvn package
```
Jar: `target/BigEnderChest.jar`
