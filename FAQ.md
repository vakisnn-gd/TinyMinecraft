# FAQ

## Как скачать игру?

Откройте страницу релизов:

[https://github.com/vakisnn-gd/TinyMinecraft/releases](https://github.com/vakisnn-gd/TinyMinecraft/releases)

Для обычной игры скачивайте файл `TinyMinecraft-v0.1-windows.zip`.

## Что скачивать: `TinyMinecraft-v0.1-windows.zip` или `Source code`?

Скачивайте `TinyMinecraft-v0.1-windows.zip`.

`Source code` - это архив исходного кода от GitHub. Он больше подходит для разработчиков, а не для обычного запуска игры.

## Как запустить игру?

1. Установите Java 17 или новее.
2. Распакуйте ZIP.
3. Запустите `run-game.bat`.

## Нужна ли Java?

Да. Для запуска нужна Java 17 или новее.

Проверить Java можно командой:

```powershell
java -version
```

## Что делать, если Windows предупреждает про `.bat` файл?

`run-game.bat` - это обычный запускатель для Windows. Он запускает Java-команду с нужными библиотеками из папки `lib/`.

## Какая версия основная?

Основная версия сейчас - `v0.1`.

## Почему старые версии помечены как Pre-release?

Версии `v0.0.0` - `v0.0.8` - это архив истории разработки. Они могут быть менее стабильными и нужны в основном для просмотра старых этапов проекта.

## Какие команды есть в игре?

Основные команды:

```text
/tp <x> <y> <z>
/time set <day|night>
/gamemode <creative|survival|spectator>
/give <id> <количество>
/spawnzombie
/seed
/locate biome <название>
/locate structure <village|mineshaft>
/place structure <name|list> [rotation]
/whereami
/probe <x> <z>
/heighttest
/blockinfo
```

Подробнее см. раздел команд в [README](README.md).

## Игра поддерживает multiplayer?

Нет. Multiplayer не планируется для `v0.1`.

## Можно ли менять код?

Да. Проект распространяется по MIT License: код можно использовать, менять, копировать и распространять при сохранении текста лицензии.
