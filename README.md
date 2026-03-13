# CodeAgent

Desktop-приложение для чата с LLM и поддержкой MCP-инструментов.

## MCP

Список MCP-серверов задаётся через `MCP_SERVERS` в `local.properties`:

## Новая фича: напоминания

Теперь `CodeAgent` поддерживает отложенные напоминания через отдельный MCP-инструмент.

Примеры пользовательских запросов:

- `напомни мне через 20 минут выпить таблетку`
- `завтра в 15 часов у меня зубной, напомни за 20 минут до этого, что пора собираться`

Как это работает:

1. Модель вызывает MCP tool `schedule_reminder`.
2. `CodeAgent` автоматически передаёт в tool текущую chat-сессию и timezone.
3. Напоминание сохраняется на стороне reminder MCP server.
4. Фоновый poller в `CodeAgent` забирает сработавшие reminders.
5. Когда время наступает, в нужной chat-сессии появляется новое сообщение ассистента с текстом напоминания.

## Запуск reminder MCP server

Из проекта [`reminder_mcp_server`](/Users/pokkerolli/claude/reminder_mcp_server):

```bash
./gradlew run
```

По умолчанию сервер стартует на `http://127.0.0.1:3002/mcp`.

Поддерживаемые переменные окружения:

- `MCP_HOST` — host для MCP HTTP endpoint
- `MCP_PORT` — port для MCP HTTP endpoint
- `REMINDER_STORE_FILE` — путь до JSON-файла с сохранёнными reminders

Если `REMINDER_STORE_FILE` не задан, используется `~/.reminder-mcp/reminders.json`.

## Что важно знать

- Напоминания переживают перезапуск reminder-сервера, потому что хранятся в JSON.
- Внутренние MCP tools для доставки (`claim_due_reminders`, `acknowledge_reminders`) скрыты от LLM и используются самим `CodeAgent`.
- Для защиты от дублей `CodeAgent` сохраняет факт уже доставленных reminders в локальной базе.
