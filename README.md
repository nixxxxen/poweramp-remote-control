# Poweramp Remote 0.7.0

Репозиторий содержит два нативных Android-приложения:

- `:app` — сервер для HiBy R4 с Poweramp-интеграцией, foreground service, REST/WebSocket API и сохранённым Web UI;
- `:phone` — новый телефонный клиент, использующий тот же API после автоматического NSD/mDNS discovery.

Версия `0.7.0` добавляет нативный Android-клиент, pairing через существующий Bearer-токен и reconnect, не удаляя и не меняя контракт Web UI/API 0.6.0. Lyrics по-прежнему не реализованы.

## Нативный Android-клиент

1. Установите серверный APK на HiBy R4 и телефонный APK на Android-телефон.
2. Откройте приложение на R4 и оставьте сервер запущенным.
3. Подключите оба устройства к одной IP-сети и откройте Poweramp Remote на телефоне.
4. Телефон автоматически найдёт R4 через Android NSD/mDNS. IP-адрес вводить не нужно.
5. Скопируйте 43-символьный Bearer-токен с экрана R4 в форму телефона.

Токен проверяется через `GET /api/v1/state` и сохраняется в приватных, исключённых из backup preferences только после успешной аутентификации. Сохраняются NSD identity и token, но не IP-адрес. При следующих запусках клиент находит известный R4, открывает Bearer WebSocket и восстанавливает соединение с ограниченной экспоненциальной задержкой.

Первый экран показывает artwork, title, artist, album, время и seekbar, codec/file type, bit depth, sample rate, raw bitrate, источник и raw позицию/размер списка. Доступны Previous, Play/Pause, Next, рейтинг `0…5`, Like, Dislike и Shuffle. Seek отправляется один раз после отпускания ползунка и ждёт подтверждённый event snapshot.

Обычные обновления приходят только через существующий WebSocket. Локально продвигается лишь отображаемая позиция; heartbeat ping/pong проверяет живость TCP, но не опрашивает playback state.

Wi-Fi Direct и Local Only Hotspot в `0.7.0` не реализованы: устройства должны находиться в одной IP-сети. Дальнейшие этапы перечислены в [`ROADMAP.md`](ROADMAP.md).

## Встроенный Web UI

1. Откройте приложение на HiBy R4, разрешите уведомления и скопируйте показанный токен.
2. На телефоне в той же локальной сети откройте `http://<IP_R4>:8765/`.
3. Введите токен один раз.

Компактная mobile-first страница показывает обложку, title/artist/album, codec/file type, bit depth, sample rate, bitrate, источник и позицию/размер списка. Доступны Previous, актуальный Play/Pause, Next, seekbar, Like, Dislike, сброс rating и Shuffle OFF/ON. На обычном портретном экране смартфона основной плеер рассчитан без вертикальной прокрутки.

После отпускания seekbar отправляется ровно одна команда seek. После первоначального REST snapshot всё состояние обновляется через WebSocket, без постоянного polling; отображаемая позиция между событиями продвигается локально.

## Локальный API

Приложение показывает на основном экране:

- запущен ли сервер;
- адрес вида `http://192.168.1.24:8765`;
- число WebSocket-клиентов;
- Bearer-токен и кнопку его копирования.

Порт фиксирован: `8765`. Для внешних API-клиентов защищённые endpoints, включая artwork и WebSocket upgrade, по-прежнему принимают заголовок:

```http
Authorization: Bearer <token>
```

Токен создаётся автоматически, сохраняется локально и не принимается через query string. Web UI отправляет его в `POST /api/v1/session` с того же origin и получает случайную `HttpOnly; SameSite=Strict` cookie на 12 часов. Токен не сохраняется в browser storage. REST, artwork и WebSocket принимают как прежний Bearer, так и валидную cookie.

### Получение состояния

```bash
BASE="http://192.168.1.24:8765"
TOKEN="вставьте-токен-с-экрана-R4"

curl -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/state"
```

Недоступные поля возвращаются как `null`. Полный JSON-формат зафиксирован в `PROJECT.md`. `bitRate` и `positionInList` передаются как raw-значения Poweramp без пересчёта.

Если поле `artwork` равно `/api/v1/artwork`, обложку можно получить так:

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/artwork" \
  --output artwork.jpg
```

### Play и pause

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"action":"play"}' \
  "$BASE/api/v1/control"

curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"action":"pause"}' \
  "$BASE/api/v1/control"
```

### Next и previous

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"action":"next"}' \
  "$BASE/api/v1/control"

curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"action":"previous"}' \
  "$BASE/api/v1/control"
```

### Seek

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"action":"seek","value":37}' \
  "$BASE/api/v1/control"
```

`value` — абсолютная позиция в целых секундах. Подтверждённая позиция приходит обратно через существующую синхронизацию Poweramp.

### Shuffle

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"action":"shuffle_on"}' \
  "$BASE/api/v1/control"

curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"action":"shuffle_off"}' \
  "$BASE/api/v1/control"
```

### Rating, Like и Dislike

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"action":"set_rating","value":4}' \
  "$BASE/api/v1/control"
```

Допустимы значения `0…5`: Like — `5`, Dislike — `1`, сброс оценки — `0`.

Успешная команда отвечает `202 Accepted`. Это подтверждает передачу команды активному Android-клиенту, а не уже произошедшее изменение в Poweramp.

## WebSocket

Endpoint: `ws://<R4-IP>:8765/api/v1/events`.

Ручная проверка с `websocat`:

```bash
websocat -H="Authorization: Bearer $TOKEN" \
  "ws://192.168.1.24:8765/api/v1/events"
```

Сразу после подключения придёт полный JSON state. Затем такой же полный объект будет приходить после событий Poweramp `TRACK_CHANGED`, `STATUS_CHANGED` и `PLAYING_MODE_CHANGED` (а также некоторых связанных изменений snapshot). Постоянного polling нет.

Для проверки:

1. Оставить `websocat` подключённым.
2. Сменить трек, поставить pause/play, изменить rating или shuffle непосредственно в Poweramp.
3. Убедиться, что приходит новый объект с увеличенным `revision` и актуальными полями.
4. Свернуть R4 Poweramp Remote и заблокировать экран R4: WebSocket должен остаться подключённым, а изменения из Poweramp должны продолжить приходить.

Встроенная страница подключает browser `WebSocket` через session cookie. Bearer-аутентификация для `websocat` и других внешних клиентов сохранена.

## Как это работает

`RemotePlaybackService` владеет `PowerampClient`, единым immutable snapshot, artwork-кэшем, browser sessions и `RemoteApiServer`. Он продолжает слушать broadcasts `TRACK_CHANGED`, `STATUS_CHANGED`, `PLAYING_MODE_CHANGED` и `TPOS_SYNC`, когда Activity свёрнута или экран заблокирован. Android UI только bind'ится к этому же состоянию; HTTP-сервер не создаёт второй слой интеграции с Poweramp.

После успешного bind порта сервер публикует `_poweramp-remote._tcp.` с TXT-полями `api=1` и стабильным публичным `id`; токен в mDNS не публикуется. Телефонный модуль не содержит Poweramp API и использует только Bearer REST/artwork/WebSocket endpoints сервера.

Сетевые команды проходят whitelist/JSON-валидацию и ставятся на главный Android-поток, где вызываются уже существующие методы `PowerampClient`. Like/Dislike используют точный `SET_RATING`, а seek — публичный `Commands.SEEK` с extra `pos` в секундах.

Обложка по-прежнему читается из content provider Poweramp и отдельно кодируется в JPEG для authenticated endpoint. Сервер построен без тяжёлого framework и ограничивает размеры запросов, frames и число соединений.

Web UI встроен как три статических asset без WebView и стороннего frontend framework. Cookie-сессии хранятся только в памяти, ограничены 16 записями и не заменяют Bearer API.

Service запускается из видимой Activity, публикует постоянное уведомление, возвращает `START_STICKY` и не останавливается при `Activity.onStop()` или удалении Activity из recent apps. Повторный start безопасен. Кнопка «Остановить» в уведомлении закрывает server/WebSocket/receivers; после явной остановки или перезагрузки устройства приложение нужно открыть снова.

Foreground service имеет тип `connectedDevice`, соответствующий локальному сетевому взаимодействию с телефоном. Wakelock и Wi-Fi lock не используются: добавлять их следует только после воспроизводимого screen-off сбоя на HiBy R4.

## Ограничения прототипа

- реальная устойчивость REST/WebSocket при длительном выключенном экране ещё должна быть подтверждена на HiBy R4; версия `0.7.0` не запрашивает wakelock/Wi-Fi lock и не просит исключение из battery optimization;
- автоматическое discovery требует общей IP-сети; Wi-Fi Direct и Local Only Hotspot оставлены следующему этапу;
- Android force-stop, явная кнопка «Остановить» и перезагрузка устройства прекращают service до следующего запуска приложения;
- HTTP и `ws://` не шифруются: использовать только в доверенной локальной сети и не открывать порт в интернет;
- проверен стандартный package Poweramp `com.maxmpz.audioplayer`;
- для рейтинга требуется Poweramp build 995 или новее;
- точные единицы `bitRate` и индексная база `posInList` ещё проверяются на R4;
- это debug-сборка, не production-релиз;
- lyrics не реализованы.

## Сборка

Требуются JDK 17 и Android SDK 36.

```powershell
./gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
```

Будут собраны два APK:

- сервер R4: `app/build/outputs/apk/debug/app-debug.apk`;
- клиент телефона: `phone/build/outputs/apk/debug/phone-debug.apk`.

Установка через ADB (выберите соответствующее устройство для каждой команды):

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r phone/build/outputs/apk/debug/phone-debug.apk
```

После установки откройте Poweramp и серверное приложение на R4. Затем откройте телефонный клиент в той же сети и введите только токен; либо продолжайте использовать сохранённый Web UI по адресу из диагностического блока R4.
