# Referência da API SmartX RFID

Esta página resume os pontos públicos que um app normalmente usa. Para configuração Gradle e fluxo completo, consulte [INTEGRACAO_ANDROID.md](INTEGRACAO_ANDROID.md).

## Pacotes principais

| Pacote                    | Uso                                                      |
| ------------------------- | -------------------------------------------------------- |
| `com.smartx.rfid.manager` | `ReaderManager`, `ReaderRegistry`, estado do gerenciador |
| `com.smartx.rfid.core`    | interface do leitor, tags, conexão e configuração        |
| `com.smartx.rfid.tags`    | coleta e filtragem de tags                               |
| `com.smartx.rfid.ble`     | permissões e descoberta de dispositivos BLE              |
| `com.smartx.rfid`         | `SmartxRfid` e versão da biblioteca                      |

## Leitores embutidos

Os IDs são estáveis e podem ser usados em `ReaderRegistry.create(...)` e `ReaderManager.findById(...)`:

| ID          | Leitor                 |
| ----------- | ---------------------- |
| `C72`       | Chainway C72           |
| `CF-H301`   | CF-H301                |
| `IH25`      | Honeywell IH25         |
| `ZEBRA`     | Zebra                  |
| `TSL1128`   | TSL1128                |
| `X714`      | UBX X714               |
| `XR2`       | XR2                    |
| `SIMULATOR` | Simulador sem hardware |

```kotlin
val todos: List<IRfidReader> = ReaderRegistry.createAll()
val selecionados = ReaderRegistry.create(listOf("C72", "SIMULATOR"))
val manager = ReaderManager(context, selecionados)
val leitor: IRfidReader? = manager.findById("SIMULATOR")
```

`ReaderRegistry.create(id)` retorna `null` para ID desconhecido. Na forma `create(ids)`, IDs desconhecidos são ignorados e registrados no log Android; uma coleção vazia significa todos os leitores.

## ReaderManager

O `ReaderManager` deve ser compartilhado pelo app enquanto as telas ou serviços precisarem da mesma conexão. Seus membros mais usados:

| Membro                                                | Tipo                            | Descrição                                                           |
| ----------------------------------------------------- | ------------------------------- | ------------------------------------------------------------------- |
| `readers`                                             | `List<IRfidReader>`             | Instâncias disponíveis                                              |
| `state`                                               | `StateFlow<ReaderManagerState>` | Conexão, inventário, configuração e erro                            |
| `activeReader`                                        | `IRfidReader?`                  | Leitor atualmente conectado                                         |
| `connectionLog`                                       | `StateFlow<List<String>>`       | Log da tentativa de conexão                                         |
| `tags`                                                | `TagCollector`                  | Coletor de tags do leitor ativo                                     |
| `findById(id)`                                        | função                          | Procura leitor pelo ID                                              |
| `connect(reader, request, maxAttempts, retryDelayMs)` | suspend                         | Conecta, observa os estados e começa a coletar tags                 |
| `disconnect()`                                        | suspend                         | Para inventário, desconecta e limpa tags                            |
| `startInventory()` / `stopInventory()`                | funções                         | Inicia ou para inventário                                           |
| `toggleInventory()`                                   | função                          | Alterna inventário; retorna se ficou ligado                         |
| `onTriggerPressed()` / `onTriggerReleased()`          | funções                         | Encaminha eventos do gatilho                                        |
| `readConfig()` / `applyConfig(config)`                | suspend                         | Lê ou aplica configuração                                           |
| `clearTags()`                                         | função                          | Limpa as tags consolidadas                                          |
| `release()`                                           | suspend                         | Desconecta e cancela o escopo do gerenciador; não reutilizar depois |

O padrão de `connect()` é até 3 tentativas com 1 segundo entre tentativas. É possível ajustar esses argumentos. A conexão deve ser chamada em coroutine.

### ReaderManagerState

`manager.state` expõe `activeReader`, `connectionState`, `isConnecting`, `isInventorying`, `config` e `lastError`. `isConnected` é uma propriedade derivada de `connectionState == CONNECTED`. Os estados de conexão são `DISCONNECTED`, `CONNECTING`, `CONNECTED` e `ERROR`.

## Tags e coleta

### RfidTag

Cada leitura é representada por `RfidTag`:

| Campo       | Tipo     | Observação                               |
| ----------- | -------- | ---------------------------------------- |
| `epc`       | `String` | EPC da etiqueta                          |
| `rssi`      | `String` | RSSI reportado pelo leitor; padrão vazio |
| `tid`       | `String` | TID, se disponível                       |
| `user`      | `String` | Memória User, se disponível              |
| `antenna`   | `Int`    | Antena reportada; padrão 1               |
| `readCount` | `Int`    | Leituras; padrão 1                       |
| `timestamp` | `Date`   | Horário da leitura                       |

### TagCollector

`manager.tags.tags` é um `StateFlow<List<RfidTag>>` consolidado, deduplicado por EPC. A lista é publicada em intervalos de até aproximadamente 200 ms. Uma releitura do mesmo EPC incrementa `readCount` e atualiza RSSI e timestamp.

`manager.tags.events` é um `SharedFlow<RfidTag>` que emite cada leitura aceita sem esperar a atualização consolidada. Use-o para ações imediatas. Outras propriedades/métodos úteis:

- `count`: total atual de EPCs únicos.
- `snapshot()`: cópia imediata da lista, sem esperar o intervalo de publicação.
- `clear()`: limpa dados consolidados.
- `filter`: filtro aplicado às próximas tags recebidas.

`TagFilter(prefixes = listOf("E200"), minRssi = -80)` aceita apenas EPCs que começam por um dos prefixos (sem diferenciar maiúsculas/minúsculas) e descarta RSSI numérico menor que o mínimo. Uma lista `prefixes` vazia aceita qualquer EPC; RSSI vazio ou não numérico não é descartado por RSSI.

## Conexão

- `ConnectionOption.INTEGRATED`: leitor integrado ao equipamento.
- `ConnectionOption.BLUETOOTH`: dispositivo BLE externo; requer seleção de dispositivo.
- `ConnectionOption.SERIAL`: opção serial/USB.
- `ConnectionRequest(option, address)`: escolha para a conexão; `address` recebe o MAC para BLE.

Cada leitor anuncia `connectionOptions` e `capabilities`. A capability `usesKeyEventTrigger` indica quando o app deve encaminhar `KeyEvent` para `onTriggerPressed()` e `onTriggerReleased()`. `inventoryStateFlow` existe em leitores que podem mudar inventário sem comando do app.

## Configuração

`ReaderConfig` possui:

- `txPower`: potência em dBm (padrão 30).
- `session`: sessão Gen2 (0 a 3; padrão 1).
- `rssiFilter`: RSSI mínimo configurável no leitor (padrão -120).
- `inventoryMode`: `EPC_ONLY`, `EPC_TID` ou `EPC_TID_USER` (padrão `EPC_TID`).
- `region`: região RF; padrão -1 (usar valor padrão do hardware quando aplicável).

Os leitores podem ter limites e suporte diferentes. Confira o retorno booleano de `applyConfig()`; a configuração unificada não garante que todo hardware implemente todos os campos.

## BLE

- `BlePermissions.required()` lista permissões runtime exigidas para a versão do Android.
- `BlePermissions.missing(context)` retorna as permissões ainda não concedidas.
- `BlePermissions.hasAll(context)` verifica se todas foram concedidas.
- `BleScanner(context).scan(timeoutMs, includeBonded)` devolve um `Flow<BleDevice>`; timeout padrão 15 segundos.
- `BleDevice` contém `name`, `address` e `isPaired`.

Solicite permissões no manifesto e em runtime antes de escanear. O fluxo pode reportar o mesmo endereço mais de uma vez; deduplicate por MAC no app.

## Simulator

O leitor `SIMULATOR` implementa o mesmo contrato de conexão, inventário, configuração e gatilho. Após conectar e iniciar inventário, emite uma leitura a cada segundo. Os EPCs começam por `00`, TIDs por `e28000` e o sufixo sequencial começa em 1. É destinado a testes de integração e UI, sem simular características físicas de rádio.
