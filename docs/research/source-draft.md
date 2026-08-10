---
id: source-draft
title: Исходный драфт задания
status: frozen
date: 2026-08-10
---

# Исходный драфт

Зафиксирован **как есть**, из `booblik.docx`. Это не руководство к действию: расхождения между
драфтом и тем, что выяснил ресёрч, разобраны в
[research-architecture](research-architecture.md), раздел 2 («Решения»). Каждое отклонение там
названо отклонением и обосновано.

Документ живёт в репозитории по одной причине: через полгода будет непонятно, почему индекс —
`LongArray`, а не `ConcurrentSkipListMap`, если не видно, что изначально предполагалось второе.

---

## Архитектурный проект: Собственный Message Broker на Kotlin (JVM)

**Кодовое название:** booblik
**Архитектурный стиль:** Lock-free, Actor Model, Append-only Log, Zero-Copy.
**Стек:** Kotlin (JVM), Coroutines, Java NIO.

### 1. Концепция и Принципы

Цель проекта — создать высокопроизводительный брокер сообщений, вдохновлённый архитектурой Kafka
(Log-based storage), но спроектированный с использованием современных идиом Kotlin.

Ключевые принципы:

* **Строгая типизация без аллокаций:** использование value classes для доменных идентификаторов.
* **Отсутствие блокировок (Lock-free):** запись в партицию контролируется корутинами-акторами.
  Никаких `Mutex` или `synchronized`.
* **Memory-Mapped IO:** максимальная скорость записи за счёт делегирования сброса на диск
  операционной системе (`MappedByteBuffer`).
* **Zero-Copy:** передача данных с диска в сеть минуя User Space (Heap) с помощью
  `FileChannel.transferTo`.

### 2. Доменная модель (Value Classes)

Чтобы избежать ошибок вида «передал Long позиции вместо Long оффсета», используем встроенные
классы (inline classes). В рантайме они компилируются в обычные примитивы, что исключает нагрузку
на Garbage Collector (GC).

```kotlin
@JvmInline value class Offset(val value: Long) { operator fun inc(): Offset = Offset(value + 1) }
@JvmInline value class Position(val value: Int)
@JvmInline value class TopicName(val value: String)
@JvmInline value class PartitionId(val value: Int)
```

### 3. Слой хранения (Storage Engine)

Хранилище разделено на независимые подсистемы: Запись (Write Path) и Чтение (Read Path).

#### 3.1. Write Path: Actor + MappedByteBuffer

Каждый активный сегмент партиции мапится в оперативную память. Актор (корутина) является
эксклюзивным владельцем этого буфера.

*Как это работает:* вместо того чтобы вызывать системные вызовы `write()` на каждое сообщение,
мы пишем данные прямо в RAM (в `MappedByteBuffer`). Ядро Linux/ОС само асинхронно сбрасывает
грязные страницы (dirty pages) на диск.

*Итог:* скорость записи ограничивается только скоростью RAM и шины.

```kotlin
class PartitionWriterActor(
    private val scope: CoroutineScope,
    private val fileChannel: FileChannel,
    private val segmentSize: Long = 1024 * 1024 * 1024, // 1 GB
) {
    private val mappedBuffer: MappedByteBuffer =
        fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, segmentSize)
    private val mailbox = Channel<WriteCommand>(capacity = Channel.BUFFERED)
    var currentOffset = Offset(0)
    var currentPosition = Position(0)

    init {
        scope.launch(Dispatchers.IO) {
            for (command in mailbox) {
                val payloadSize = command.payload.size
                mappedBuffer.putInt(payloadSize)
                mappedBuffer.put(command.payload)
                val recordPosition = currentPosition
                currentPosition = Position(currentPosition.value + 4 + payloadSize)
                val recordOffset = currentOffset
                currentOffset++
                // Index.put(recordOffset, recordPosition)
                command.ack.complete(recordOffset)
            }
        }
    }

    suspend fun append(payload: ByteArray): Offset {
        val ack = CompletableDeferred<Offset>()
        mailbox.send(WriteCommand(payload, ack))
        return ack.await()
    }
}

class WriteCommand(val payload: ByteArray, val ack: CompletableDeferred<Offset>)
```

#### 3.2. Read Path: Zero-Copy + FileChannel.transferTo

Чтение происходит параллельно, вне Актора записи. Когда потребитель запрашивает данные, брокер
не загружает байты сообщений в память JVM.

*Как это работает:* мы находим нужную позицию в индексном файле (или in-memory структуре). Затем
используем `transferTo()`. Под капотом (на Linux) это вызывает системный вызов `sendfile()`.

*Итог:* данные копируются напрямую из кэша ОС (Page Cache) в буфер сетевого сокета (Socket
Buffer). Процессор почти не участвует, GC отдыхает.

```kotlin
class ReaderService(private val dataFileChannel: FileChannel) {
    fun streamToNetwork(clientSocket: SocketChannel, startPosition: Position, bytesToRead: Long) {
        dataFileChannel.transferTo(startPosition.value.toLong(), bytesToRead, clientSocket)
    }
}
```

### 4. Сетевой слой (Network & TCP)

Для поддержания тысяч TCP-соединений используем модуль Ktor Network (основанный на NIO и
корутинах).

* **Acceptor Корутина:** слушает порт и при подключении нового клиента порождает новую
  корутину-обработчик (Session).
* **Dispatcher:** сетевые корутины парсят бинарный протокол (например,
  `[Тип запроса][Длина][Payload]`).
  * Если это `PUBLISH` — запрос передаётся в `PartitionWriterActor.append()`.
  * Если это `SUBSCRIBE` — запрос передаётся в `ReaderService`, который дёргает `transferTo`,
    передавая `SocketChannel` из Ktor (для прямого доступа к NIO-каналу потребуется извлечь
    underlying channel).

### 5. План реализации (Milestones)

| Этап | Задачи | Результат |
|---|---|---|
| Milestone 1: База | Настроить проект. Реализовать value classes. Сделать `PartitionWriterActor` на основе `MappedByteBuffer` для одного топика. | Можно писать байты, они быстро сохраняются на диск через память ОС. |
| Milestone 2: Индекс | Написать потокобезопасный In-Memory индекс `ConcurrentSkipListMap<Offset, Position>`. | Брокер знает, по какому байтовому смещению лежит конкретный Offset. |
| Milestone 3: Zero-Copy | Поднять `ServerSocketChannel`. Реализовать обработку команды `FETCH` через `FileChannel.transferTo`. | Клиент может по TCP запросить батч сообщений и моментально их получить. |
| Milestone 4: Сеть | Разработать бинарный протокол. Прикрутить Ktor Network для удобного управления корутинами-сессиями. | Готовый MVP брокера. Можно писать клиентские библиотеки. |
