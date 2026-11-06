# SmartX RFID (Android)

Biblioteca Android unificada para leitores RFID UHF. O app conversa com **uma única API** (`ReaderManager` + `IRfidReader`) e novos leitores são adicionados **somente aqui**, sem alterar os apps.

Leitores suportados: `C72` (Chainway), `CF-H301`, `IH25` (Honeywell), `ZEBRA`, `TSL1128`, `X714` (UBX), `XR2`.

Tudo que muda entre versões/ambientes (versão, coordenadas Maven, SDKs de fabricantes, repositório) fica em [config.json](config.json).

## Uso

### 1. Dependência

```groovy
// app/build.gradle
dependencies {
    implementation "com.smartx.rfid:smartx-rfid:1.0.0"
}
```

O app precisa dos repositórios `google()`, `mavenCentral()` e do GitHub Packages (veja [Consumindo a lib](#consumindo-a-lib)).

### 2. Criar o manager (uma vez por processo)

```kotlin
class MyApp : Application() {
    lateinit var readers: ReaderManager

    override fun onCreate() {
        super.onCreate()
        // todos os leitores...
        readers = ReaderManager(this)
        // ...ou só alguns, por id
        readers = ReaderManager(this, ReaderRegistry.create(listOf("C72", "ZEBRA")))
    }
}
```

### 3. Conectar, inventariar e observar

```kotlin
val reader = manager.findById("ZEBRA")!!

// Opções de conexão que o leitor oferece (integrado, bluetooth, serial...)
reader.connectionOptions                       // List<ConnectionOption>
reader.capabilities                            // ReaderCapabilities

lifecycleScope.launch {
    val ok = manager.connect(reader, ConnectionRequest(ConnectionOption.BLUETOOTH, "AA:BB:CC:DD:EE:FF"))
}

manager.state.collect { s -> /* activeReader, connectionState, isConnecting, isInventorying, config, lastError */ }
manager.connectionLog.collect { lines -> /* log da conexão */ }
manager.tags.tags.collect { list -> /* lista deduplicada por EPC, atualizada ~a cada 200 ms */ }

manager.toggleInventory()
manager.applyConfig(ReaderConfig(txPower = 25, session = 1))
manager.disconnect()
```

### 4. Gatilho físico

Leitores com `capabilities.usesKeyEventTrigger` recebem o gatilho como `KeyEvent` (ex.: C72). No `Activity`:

```kotlin
override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
    if (manager.onTriggerPressed()) true else super.onKeyDown(keyCode, event)

override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
    if (manager.onTriggerReleased()) true else super.onKeyUp(keyCode, event)
```

### 5. Bluetooth LE

```kotlin
BlePermissions.missing(context)                // permissões que faltam (API-aware)
BleScanner(context).scan().collect { device -> /* BleDevice(name, address, isPaired) */ }
```

## Estrutura

```
smartx-rfid/src/main/java/com/smartx/rfid/
├── core/        IRfidReader, RfidTag, ReaderConfig, ReaderCapabilities, ConnectionOption...
├── manager/     ReaderManager, ReaderManagerState, ReaderRegistry
├── tags/        TagCollector (dedup, throttle), TagFilter
├── ble/         BleScanner, BlePermissions
└── devices/     um pacote por leitor (c72, cfh301, ih25, tsl1128, x714, xr2, zebra)
vendor-sdks/     AAR/JAR de fabricantes empacotados como artefatos Maven
scripts/         commit, build, publicação
config.json      versão, coordenadas Maven, SDKs
```

## Adicionando um novo leitor

1. Crie `devices/<modelo>/<Modelo>Reader.kt` implementando `IRfidReader` (veja `C72Reader` como exemplo simples).
   - Declare `connectionOptions` e `capabilities`; o app se adapta sozinho (diálogo de conexão, gatilho, reconexão).
2. Se houver SDK do fabricante: copie o AAR/JAR para `vendor-sdks/aars/`, adicione uma entrada em `config.json -> vendorSdks.artifacts` e rode `./scripts/publish_sdks.sh`.
3. Registre em `ReaderRegistry.builtIns`.
4. Escreva/rode testes (`./scripts/build.sh`), rode `./scripts/commit.sh` (sobe versão, tag e push).
5. Nos apps, basta atualizar `library.version` no `config.json` do app.

## Scripts

| Script                       | O que faz                                                                                                                                |
| ---------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `scripts/init_repo.sh`       | `git init`, remote `origin` (config.json) e commit/tag inicial.                                                                          |
| `scripts/commit.sh`          | Escolhe major/minor/patch, atualiza `config.json` e `CHANGELOG.md`, commita, cria a tag `vX.Y.Z` e faz push. A tag dispara a publicação. |
| `scripts/get_remote_repo.sh` | Descarta alterações locais e iguala ao `origin/main`.                                                                                    |
| `scripts/build.sh`           | Testes unitários + AAR release (`--no-tests` para pular testes).                                                                         |
| `scripts/publish_sdks.sh`    | Regenera `vendor-sdks/repo` (`--remote` também publica no GitHub Packages).                                                              |
| `scripts/publish_local.sh`   | Publica lib + SDKs no `~/.m2` para testar sem subir nada.                                                                                |
| `scripts/publish.sh`         | Publica manualmente no GitHub Packages (`--with-sdks` inclui os SDKs).                                                                   |

## Publicação

### Primeira vez

```bash
./scripts/init_repo.sh                      # cria o repo local e aponta para o GitHub
git push -u origin main && git push origin v1.0.0
```

Ao subir a tag `vX.Y.Z`, o workflow [publish.yml](.github/workflows/publish.yml) publica a lib e os SDKs no GitHub Packages usando o `GITHUB_TOKEN` (nada a configurar).

### Releases seguintes

```bash
./scripts/commit.sh
```

### Publicar manualmente

```bash
export GITHUB_ACTOR=seu-usuario
export GITHUB_TOKEN=ghp_xxx      # PAT com write:packages
./scripts/publish.sh --with-sdks
```

## Consumindo a lib

O GitHub Packages exige autenticação mesmo para leitura (PAT com `read:packages`).

```groovy
// settings.gradle do app
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/ghpascon/smartx-rfid-android")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull
                password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}
```

Em `~/.gradle/gradle.properties`:

```
gpr.user=SEU_USUARIO
gpr.key=SEU_TOKEN
```

Para desenvolver lib e app juntos, use `includeBuild("../smartx-rfid-android")` (composite build) e o repositório `vendor-sdks/repo` — o X-SCAN faz isso via `library.source = "local"` em `rfid-app/config.json`.

## Observações

- **SDKs de fabricantes**: os AAR/JAR em `vendor-sdks/aars` são de terceiros. Confirme a licença de redistribuição antes de tornar o repositório ou os pacotes públicos (mantenha o repo privado se necessário).
- O AGP não empacota AAR local dentro de AAR de biblioteca; por isso os SDKs viram artefatos Maven (`com.smartx.rfid.sdk:*`) e a lib depende deles por coordenadas.
- Publicar uma nova versão **não atualiza apps já instalados**: é preciso gerar e distribuir um novo build do app.
- `android.enableJetifier=true` é necessário no app (o SDK TSL ainda usa `android.support`).
- Requisitos: JDK 17, Android SDK 34 (`local.properties` com `sdk.dir`), `jq` para os scripts.
