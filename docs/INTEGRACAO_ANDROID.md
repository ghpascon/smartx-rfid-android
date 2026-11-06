# Integração da SmartX RFID em um app Android

Este guia explica como adicionar a biblioteca a um app Android Kotlin, selecionar um leitor, conectar, ler tags e encerrar a conexão. A integração usa `ReaderManager`; o app não precisa chamar diretamente os SDKs dos fabricantes.

## Requisitos

- Android com `minSdk 26` ou superior.
- JDK 17 e Android Gradle Plugin compatível.
- Acesso ao GitHub Packages para baixar a dependência. O GitHub Packages exige autenticação também para leitura.
- Para leitores BLE, permissões Bluetooth declaradas e concedidas em runtime.

O exemplo usa a versão `1.1.0`; confirme a versão publicada mais recente antes de integrar.

## 1. Configurar o repositório Maven

No `settings.gradle.kts` do app (ou `settings.gradle` em Groovy), inclua o repositório junto a `google()` e `mavenCentral()`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/ghpascon/smartx-rfid-android")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

Configure credenciais locais em `~/.gradle/gradle.properties`, nunca no controle de versão:

```properties
gpr.user=SEU_USUARIO_GITHUB
gpr.key=SEU_TOKEN_COM_READ_PACKAGES
```

Em CI, use secrets protegidos expostos como `GITHUB_ACTOR` e `GITHUB_TOKEN`. O token precisa de `read:packages` e o usuário precisa ter acesso ao pacote.

## 2. Adicionar a dependência

No `build.gradle.kts` do módulo app:

```kotlin
dependencies {
    implementation("com.smartx.rfid:smartx-rfid:1.1.0")
}
```

Os SDKs de fabricantes são dependências transitivas. Não adicione os AARs manualmente ao app. Se o app precisar das bibliotecas legadas usadas pelo SDK TSL, habilite Jetifier no `gradle.properties`:

```properties
android.enableJetifier=true
```

## 3. Criar e compartilhar o gerenciador

Mantenha uma instância de `ReaderManager` durante o processo do app, por exemplo na classe `Application`. Selecione somente os leitores necessários:

```kotlin
class RfidApplication : Application() {
    lateinit var readerManager: ReaderManager
        private set

    override fun onCreate() {
        super.onCreate()
        readerManager = ReaderManager(
            context = this,
            readers = ReaderRegistry.create(listOf("SIMULATOR", "ZEBRA", "C72"))
        )
    }
}
```

Declare a classe no `AndroidManifest.xml`:

```xml
<application
    android:name=".RfidApplication"
    ... >
</application>
```

A tela pode obter a instância da `Application` (ou usar o mecanismo de injeção de dependência do app):

```kotlin
val manager = (application as RfidApplication).readerManager
```

Sem lista de IDs, `ReaderManager(context)` cria todos os leitores embutidos. Veja a [referência da API](REFERENCIA_API.md) para a lista de IDs.

## 4. Conectar

Cada leitor expõe `connectionOptions` e `capabilities`. Para leitor integrado, a conexão pode usar a opção padrão:

```kotlin
val reader = manager.findById("SIMULATOR") ?: return

lifecycleScope.launch {
    val connected = manager.connect(reader)
    if (!connected) {
        // Exiba manager.state.value.lastError ou manager.connectionLog.value.
    }
}
```

Para leitor BLE, passe a opção selecionada e o endereço MAC:

```kotlin
val request = ConnectionRequest(
    option = ConnectionOption.BLUETOOTH,
    address = selectedDevice.address
)

lifecycleScope.launch {
    manager.connect(reader, request)
}
```

`connect()` é uma função suspensa e deve ser chamada em coroutine. Por padrão, tenta conectar até três vezes, lê/aplica a configuração e começa a observar as tags e os estados do leitor.

## 5. Descobrir dispositivos BLE

Antes do scan, declare as permissões no manifesto e solicite em runtime as permissões retornadas por `BlePermissions.missing(context)`. Android 12 ou superior requer `BLUETOOTH_SCAN` e `BLUETOOTH_CONNECT`; versões anteriores usam `ACCESS_FINE_LOCATION` para scan.

Depois de conceder as permissões:

```kotlin
lifecycleScope.launch {
    if (BlePermissions.missing(this@ReaderActivity).isNotEmpty()) {
        // Solicite as permissões runtime antes do scan.
        return@launch
    }

    BleScanner(this@ReaderActivity).scan().collect { device ->
        // Mostre device.name e device.address para seleção.
    }
}
```

O scan dura 15 segundos por padrão, pode incluir dispositivos pareados e pode reportar o mesmo endereço mais de uma vez. Deduplicate por `address` na interface.

## 6. Observar estado e tags

Colete os `StateFlow`s respeitando o ciclo de vida da tela. Este exemplo usa `lifecycle-runtime-ktx`:

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
            manager.state.collect { state ->
                // state.isConnected, state.isConnecting, state.isInventorying,
                // state.activeReader, state.config, state.lastError
            }
        }
        launch {
            manager.tags.tags.collect { tags ->
                // Lista consolidada por EPC, adequada para atualizar a UI.
            }
        }
        launch {
            manager.tags.events.collect { tag ->
                // Cada leitura aceita, sem o throttle da lista consolidada.
            }
        }
    }
}
```

`manager.tags.tags` publica a lista consolidada aproximadamente a cada 200 ms. `manager.tags.events` emite cada leitura aceita imediatamente; use para contadores, feedback ou efeitos sonoros.

## 7. Inventário e gatilho

```kotlin
val started = manager.startInventory()
// Alternativa: manager.toggleInventory()

manager.stopInventory()
```

`startInventory()` retorna `false` se não houver leitor ativo ou se o leitor não iniciar. O estado atual aparece em `manager.state.value.isInventorying`.

Se `reader.capabilities.usesKeyEventTrigger` for `true`, encaminhe as teclas físicas pela Activity:

```kotlin
override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
    if (manager.onTriggerPressed()) true else super.onKeyDown(keyCode, event)

override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
    if (manager.onTriggerReleased()) true else super.onKeyUp(keyCode, event)
```

## 8. Configuração e filtros

Leia ou aplique a configuração do leitor conectado:

```kotlin
lifecycleScope.launch {
    val current = manager.readConfig()
    val applied = manager.applyConfig(ReaderConfig(txPower = 25, session = 1))
}
```

`ReaderConfig` inclui potência, sessão Gen2, RSSI mínimo, modo de inventário e região. Nem todo hardware permite todos os parâmetros; verifique o retorno de `applyConfig()`.

Para filtrar as tags recebidas:

```kotlin
manager.tags.filter = TagFilter(
    minRssi = -80,
    prefixes = listOf("E200", "3008")
)
```

O coletor deduplica por EPC e incrementa `readCount` em releituras. Use `manager.clearTags()` para limpar a lista.

## 9. Desconectar

```kotlin
lifecycleScope.launch {
    manager.disconnect()
}
```

`disconnect()` para o inventário, desconecta o leitor e limpa as tags. `release()` também cancela o escopo interno e só deve ser chamado quando o gerenciador não for mais reutilizado; não o chame ao destruir uma tela se o `ReaderManager` pertence à `Application`.

## 10. Testar sem hardware

Inclua `SIMULATOR` na lista de leitores, conecte e inicie o inventário. O simulador emite uma tag por segundo com EPC iniciado por `00` e TID iniciado por `e28000`, incrementando o sufixo a partir de 1. É útil para testar UI e fluxo de integração, mas não representa alcance, potência ou comportamento RF de um leitor real.

## Problemas comuns

- **Erro HTTP 401 ao baixar dependência:** confira URL, usuário, token `read:packages` e acesso ao pacote. Nunca versione tokens.
- **BLE não encontra dispositivos:** confirme Bluetooth ligado, permissões de manifesto e runtime concedidas e scan coletado em coroutine.
- **Tags não aparecem:** verifique conexão, retorno de `startInventory()` e o filtro configurado.
- **Configuração não aplicada:** alguns leitores não suportam todos os parâmetros; valide o retorno e teste no hardware correspondente.
