# FAQ

## Что это за проект?

TinyCraft / TinyMinecraft - маленькая Java/LWJGL voxel-песочница в стиле Minecraft. В проекте есть чанковый мир, биомы, пещеры, шахты, деревни, мобы, инвентарь, крафт, сундуки, печки, команды, LAN-мультиплеер и первый dedicated server.

## Какая версия сейчас актуальная?

Актуальный prerelease разработки - `v0.2 Snapshot 8`.

Последний stable-релиз на GitHub может отображаться отдельно. Snapshot 8 публикуется как prerelease, потому что multiplayer и dedicated server еще имеют честные ограничения.

## Где скачать игру?

Откройте страницу релизов:

[https://github.com/vakisnn-gd/TinyMinecraft/releases](https://github.com/vakisnn-gd/TinyMinecraft/releases)

Для Snapshot 8 используйте релиз:

- `v0.2 Snapshot 8`
- tag: `v0.2-snapshot8`
- asset: `TinyMinecraft-v0.2-snapshot8-windows.zip`

Скачивайте именно `.zip` из assets. `Source code` - это архив исходников от GitHub, он больше подходит для разработки.

## Какая Java нужна?

Нужна Java 8 или новее. Проект собирается командой с `--release 8`, поэтому совместим с Java 8 runtime.

Проверить Java:

```powershell
java -version
```

## Как собрать и запустить из исходников?

```powershell
javac -encoding UTF-8 --release 8 -cp "lib/*" -d out *.java
java -cp "out;lib/*" TinyCraft
```

На Windows также можно запустить:

```powershell
.\run-game.bat
```

## Как запустить dedicated server?

```powershell
javac -encoding UTF-8 --release 8 -cp "lib/*" -d out *.java
java -cp "out;lib/*" TinyCraftServer --world server_world --port 25566
```

На Windows:

```powershell
.\run-server.bat
```

Сервер создает локальный `server.properties` и хранит мир в `saves/<world>`. Если запускать сервер снова с тем же `world`, он откроет тот же мир.

## Поддерживается ли мультиплеер?

Да. Snapshot 8 поддерживает Direct IP / LAN и dedicated server MVP.

Работает:

- подключение к `127.0.0.1` на одном ПК;
- подключение в локальной сети по IP;
- подключение к dedicated server при открытом TCP-порту;
- синхронизация чанков, блоков, игроков, мобов, дропа, времени и чата;
- Tab player list с ping и здоровьем;
- команды `/list`, `/ping`, `/msg`, `/kick`;
- базовый PvP и server-side mob attack.

Пока не готово:

- публичный список серверов;
- relay/NAT traversal;
- внешние аккаунты;
- полноценный anti-cheat;
- полная server-side модель контейнеров и всего инвентаря;
- никнеймы над игроками.

## Как подключиться к серверу?

1. Запустите `TinyCraftServer`.
2. Запустите `TinyCraft`.
3. Откройте "Мультиплеер".
4. Введите IP сервера. На том же ПК используйте `127.0.0.1`.
5. Порт оставьте `25566`, если он не менялся.
6. Нажмите "Подключиться".

Для подключения через интернет нужно вручную настроить firewall, port-forward или VPN.

## Что такое `profile.properties`?

Это локальный профиль игрока:

- UUID;
- ник.

Файл создается автоматически и не должен попадать в GitHub. Если две копии игры используют один и тот же `profile.properties`, сервер отклонит вторую как `Duplicate player uuid`.

## Какие команды есть в multiplayer?

```text
/list
/ping
/msg <player> <message>
/kick <player> [reason]
```

`/kick` доступен host-стороне. На dedicated server игроки получают обратную связь через чат, а console admin может использовать `kick <player> [reason]`.

## Почему релиз только Windows?

В репозитории сейчас лежат LWJGL natives только для Windows. Linux/macOS packaging можно добавить позже, но Snapshot 8 целится в Windows zip.

## Можно ли менять код?

Да. Проект распространяется по MIT License.
