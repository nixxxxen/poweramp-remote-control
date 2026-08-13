# Poweramp Remote

Два нативных Android-приложения с независимыми версиями:

- Server `0.9.0` (`:app`) — устанавливается на Android-устройство с Poweramp;
- Phone Client `0.3.0` (`:phone`) — управляет Server через обратно совместимый API `v1`.

Оба APK сейчас используют `versionCode 10`; счётчики объявлены отдельно в модулях и дальше
увеличиваются независимо.

Server сохраняет foreground service, Poweramp Intent API, REST/WebSocket API, Bearer/session auth,
LAN NSD и встроенный Web UI. Phone Client предпочитает обычную LAN, а для уже привязанного Server
может автоматически перейти к Wi-Fi Direct.

## Быстрый старт

### Первая привязка

1. Установите Server APK на Player device и Phone Client APK на телефон.
2. Запустите Poweramp и Poweramp Remote Server.
3. Подключите оба устройства к одной IP-сети.
4. Откройте Phone Client: он найдёт Server через NSD/mDNS.
5. Скопируйте 43-символьный Bearer-токен с экрана Server и вставьте его в Phone Client.

Клиент проверяет токен запросом `GET /api/v1/state`, затем сохраняет только стабильный публичный
Server `id`, имя сервиса и токен в приватных preferences без backup. IP-адрес не сохраняется.

### Автоматическое соединение

При следующих запусках Phone Client сначала ищет известный `id` через обычный LAN NSD. Если Server
не найден, запускаются Wi-Fi Direct peer discovery и DNS-SD service discovery. Для этого не нужно
открывать системный экран Wi-Fi Direct на Player device. Android может показать системное
подтверждение на одном из устройств — его нужно принять вручную. Приложение не обходит обязательные
диалоги.

Для Wi-Fi Direct нужны:

- Android 13+: разрешение «Устройства поблизости»;
- Android 8–12L: разрешения геолокации и включённый системный режим Location;
- включённый Wi-Fi на обоих устройствах;
- поддержка Wi-Fi Direct производителем.

Permission-модель соответствует официальным руководствам Android по
[Nearby Wi-Fi devices](https://developer.android.com/develop/connectivity/wifi/wifi-permissions) и
[Wi-Fi Direct service discovery](https://developer.android.com/develop/connectivity/wifi/nsd-wifi-direct).

Если permission, Wi-Fi, Location Mode, group negotiation или системное подтверждение мешают
автоподключению, Phone Client показывает конкретное состояние и действие. После восстановления
обычной сети LAN discovery и reconnect запускаются автоматически.

Phone Client держит NSD, P2P channel/group, reconnect, WebSocket и системную MediaSession в одном
`connectedDevice|mediaPlayback` foreground service с постоянным уведомлением. Сворачивание Activity
и блокировка телефона не останавливают соединение. Явный Stop в уведомлении завершает runtime;
keep-screen-on, wakelock и Wi-Fi lock не используются.

Первая привязка остаётся LAN-only: Wi-Fi Direct fallback принимает только уже проверенный Server
`id`, чтобы не предлагать токен неизвестному nearby-устройству.

## Phone Client UI

Экран показывает artwork, title, artist, album, elapsed/duration, codec/file type, bit depth, sample
rate, bitrate, источник и позицию списка. Bitrate отображается в `кбит/с`; позиция — как
человекочитаемое `current / total`, без `raw`. API v1 при этом продолжает передавать исходные
Poweramp `bitRate` и `positionInList` без изменения.

Доступны Previous, Play/Pause, Next, one-shot seek, rating `0…5`, Like, Dislike, Shuffle и компактная
громкость Android media stream на Player device. Команды идут через REST, а подтверждённое
состояние — полными WebSocket snapshots без polling. Phone Client не меняет громкость телефона.

Phone Client также публикует title, artist, album, artwork, playing/paused, duration и position через
Media3 MediaSession. Системные Android/lock-screen и совместимые Wear OS controls отправляют
Previous, Play/Pause, Next и Seek тому же Server. Клиент не содержит ExoPlayer, не воспроизводит
аудио и не запрашивает audio focus.

## Web UI

Встроенный Web UI не удалён. В общей LAN откройте:

```text
http://<SERVER-IP>:8765/
```

Введите токен с экрана Server. Страница обменяет его через `POST /api/v1/session` на
`HttpOnly; SameSite=Strict` cookie. Токен не сохраняется в browser storage. Web UI сохраняет все
прежние controls и добавляет синхронизированный slider громкости Player device.

## API v1

Порт: `8765`.

| Method | Path | Назначение |
|---|---|---|
| `GET` | `/` | Встроенный Web UI |
| `POST` | `/api/v1/session` | Browser session login |
| `DELETE` | `/api/v1/session` | Logout |
| `GET` | `/api/v1/state` | Полный state JSON |
| `POST` | `/api/v1/control` | Команда, успех — `202 Accepted` |
| WebSocket `GET` | `/api/v1/events` | Начальный и последующие полные snapshots |
| `GET` | `/api/v1/artwork` | Текущая JPEG-обложка |

Bearer-аутентификация:

```http
Authorization: Bearer <token>
```

Пример:

```powershell
$base = "http://192.168.1.24:8765"
$token = "токен-с-экрана-Server"
curl.exe -H "Authorization: Bearer $token" "$base/api/v1/state"
```

Команды:

```json
{"action":"play"}
{"action":"pause"}
{"action":"previous"}
{"action":"next"}
{"action":"seek","value":37}
{"action":"shuffle_on"}
{"action":"shuffle_off"}
{"action":"set_rating","value":4}
{"action":"set_volume","value":7}
```

`202 Accepted` подтверждает передачу валидной команды активному Android-клиенту, а не уже
применённое Poweramp-состояние. Результат приходит через событие Poweramp и WebSocket snapshot.

`bitRate` и `positionInList` остаются исходными значениями Poweramp: их точная единица/индексная
база публично не гарантированы. Преобразование для удобства выполняется только в UI. Новые optional
поля `volume`, `volumeMax` и `volumeControlAvailable` дополняют прежний объект API v1, поэтому старые
клиенты могут их игнорировать.

В проверенной публичной версии Poweramp Intent API нет команды volume. Поэтому Server использует
только системный `AudioManager.STREAM_MUSIC` Player device и отслеживает его изменения; внутренние
Poweramp DSP-константы не используются.

## Discovery

LAN NSD публикует `_poweramp-remote._tcp.` с TXT `api=1` и стабильным публичным `id`.
Wi-Fi Direct DNS-SD публикует тот же `id`, `api=1` и `port=8765`. Bearer-токен не публикуется ни в
одном discovery transport.

Server после публикации запускает и периодически обновляет `discoverPeers()`. Phone запускает
`discoverPeers()`, затем добавляет DNS-SD request и вызывает `discoverServices()`. Оба приложения
наблюдают discovery/connection/channel state в течение жизни foreground service и автоматически
возобновляют discovery после временной остановки или потери уже установленной группы. При исчезновении
общей Wi-Fi/Ethernet сети Phone инвалидирует старый LAN endpoint и пересоздаёт P2P channel/discovery,
а Server переопубликовывает DNS-SD service; наличие мобильной сети больше не блокирует fallback.

После формирования P2P-группы Phone Client использует group-owner address и тот же HTTP/WebSocket
API v1. Server должен стать group owner; клиент запрашивает минимальный phone group-owner intent,
но итог выбирает Android. Если владельцем группы стал телефон, UI предлагает повторить попытку.

## Сборка

Нужны JDK 17 и Android SDK 36. Из корня проекта:

```powershell
./gradlew.bat clean :app:testDebugUnitTest :phone:testDebugUnitTest `
  :app:lintDebug :phone:lintDebug :app:assembleDebug :phone:assembleDebug `
  --no-build-cache --console=plain
```

APK:

- `app/build/outputs/apk/debug/app-debug.apk` — Server `0.9.0`;
- `phone/build/outputs/apk/debug/phone-debug.apk` — Phone Client `0.3.0`.

Исторические `applicationId` `dev.r4remote.poweramp` и `dev.r4remote.poweramp.phone` сохранены ради
обновления существующих установок без потери Server token и Phone pairing. Исходные namespace,
названия продукта и UI больше не привязаны к конкретной модели устройства.

## Ограничения

- HTTP и `ws://` не имеют application-layer encryption; используйте доверенную локальную сеть и не
  открывайте порт `8765` в интернет.
- Wi-Fi Direct, vendor group-owner behavior и reconnect требуют проверки на реальных устройствах.
- Force-stop, Stop в уведомлении или перезагрузка останавливают соответствующий runtime до
  следующего запуска приложения.
- Wakelock и Wi-Fi lock не используются без воспроизводимого device-specific сбоя.
- Lyrics намеренно не реализованы.

Подробная архитектура — в [`PROJECT.md`](PROJECT.md), текущая проверка — в
[`STATUS.md`](STATUS.md), дальнейшая работа — в [`ROADMAP.md`](ROADMAP.md).
