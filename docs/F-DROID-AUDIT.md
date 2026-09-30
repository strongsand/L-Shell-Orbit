# Auditoria de preparação para o F-Droid — L-Shell Orbit

> Nota de atualização — 2026-09-30: este relatório preserva o diagnóstico histórico de 2026-09-26. O repositório público e sete screenshots pt-BR já existem; a configuração atual é `0.1.1-beta` / código `2`, com R8 habilitado, `isShrinkResources=false` e a regra Protobuf Lite necessária. O mantenedor informou testes/builds locais e validação física posteriores bem-sucedidos. Também confirmou manualmente que o pipeline mais recente do MR oficial !50362 passou completamente, que reproducible builds estão configuradas e que o APK upstream assinado publicado em `v0.1.1-beta` passou pelo `check apk` reproduzível da CI. O mantenedor do F-Droid considera o MR "mostly ready", aguardando teste/revisão manual e merge condicionado ao sucesso; o app ainda não está oficialmente aceito ou publicado no F-Droid. As pendências e configurações antigas abaixo não são instruções atuais. O [checkpoint público de release](RELEASE-CHECKLIST.md#current-release-checkpoint--011-beta) registra o APK, assinatura, ferramentas e campos F-Droid confirmados.

Jobs aprovados no pipeline mais recente, conforme confirmação manual do mantenedor em 2026-09-30: `fdroid build`, `check apk`, `check source code`, `checkupdates`, `fdroid lint`, `fdroid rewritemeta`, `git redirect`, `schema validation` e `tools check scripts`. O MR oficial é [!50362](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50362); aprovação da CI não equivale a merge ou publicação.

Data da última revisão: 26 de setembro de 2026.

Escopo: fontes, configuração Gradle, manifesto, dependências, recursos, rede, privacidade e artefatos presentes na árvore local. Não foram executados Gradle Sync, instalação ou o aplicativo. A validação autorizada pela linha de comando foi iniciada, mas o ambiente desta sessão bloqueou o `javac` antes da execução dos testes.

## Status geral

**Quase pronto.** O projeto está licenciado sob `GPL-3.0-or-later`, a procedência conhecida do schema gRPC está registrada, o CelesTrak usa o trust store padrão e o wrapper Gradle 8.13 foi verificado contra a publicação oficial. A metadata upstream foi preparada. Ainda faltam um repositório público versionado, screenshots reais e a validação dos testes/build em um terminal sem a restrição ZipFS observada nesta sessão.

### Identidade técnica atual

- Nome público: **L-Shell Orbit**.
- Nome público anterior: **L-Shell**. A alteração para L-Shell Orbit foi somente de branding.
- `applicationId`: `io.github.strongsand.lshell`.
- `namespace`: `io.github.strongsand.lshell`.
- Packages Kotlin e package dos bindings gerados: `io.github.strongsand.lshell` e `io.github.strongsand.lshell.proto`.
- Versão da primeira beta sob a nova identidade: `versionName = 0.1.0-beta`, `versionCode = 1`.
- O package de protocolo `SpaceX.API.Device` permanece inalterado porque faz parte da interoperabilidade gRPC, não do branding do aplicativo.
- A troca de `applicationId` faz o Android tratar L-Shell Orbit como uma instalação diferente de builds antigos. Banco e preferências da instalação anterior não migram automaticamente.

## Bloqueadores

1. **Não há repositório público/VCS detectável nesta cópia.** A submissão precisa de URL pública do código, histórico, tags e fonte correspondente à versão publicada.
2. **Artefatos que não devem entrar no repositório público.** Há um APK pronto em `app/release/`, duas páginas HTML baixadas na raiz e diretórios de build/IDE. Eles estão ignorados por `.gitignore`, mas devem ser mantidos fora do primeiro commit público. O APK antigo usa `DishyDash-beta-1.0.1.apk`, enquanto a nova identidade do fonte é L-Shell Orbit `0.1.0-beta`.

## Atenção

- `usesCleartextTraffic="true"` está habilitado globalmente porque os endpoints gRPC locais usam texto claro. Para endurecimento futuro, avalie limitar cleartext somente aos hosts locais, mas apenas depois de validar todas as variantes de rede suportadas.
- O User-Agent usado para baixar o catálogo orbital imita um Chrome Android. Não é rastreamento, porém um identificador honesto do projeto seria mais transparente numa revisão futura.
- `namespace` e `applicationId` foram consolidados em `io.github.strongsand.lshell`. O identificador é tecnicamente válido; confirme que será o identificador estável e controlado pelo mantenedor antes da primeira publicação.
- `versionCode = 1` e `versionName = 0.1.0-beta` representam a primeira beta pública da nova identidade. Cada release posterior precisa incrementar o código e ter uma tag correspondente, como `v0.1.0-beta`.
- A procedência de `device.proto` foi documentada em `docs/DEVICE_PROTO_PROVENANCE.md`. O histórico anterior foi informado pelo mantenedor e é coerente com o cabeçalho antigo, mas não pôde ser inteiramente confirmado por VCS nesta cópia. A origem observável do protocolo continua sendo um item revisável, não uma alegação de schema oficial.
- Não há dependency locking ou verification metadata. As versões declaradas são fixas e foi adicionada a soma SHA-256 da distribuição Gradle, mas locks/verificação podem aumentar a auditabilidade.
- `ACCESS_NETWORK_STATE` não tem uso direto evidente no fonte. `CHANGE_NETWORK_STATE` parece ser mantida como requisito associado ao foreground service do tipo `connectedDevice`. Confirme ambas em dispositivos suportados antes de considerar qualquer remoção.
- A marca Starlink é usada para descrever compatibilidade e há um ícone próprio estilizado. Mantenha o aviso de projeto independente e evite logotipos ou aparência de produto oficial.
- O F-Droid pode avaliar se a interoperabilidade com hardware/ecossistema proprietário merece `NonFreeNet`. Isso não é `NonFreeDep`: não foi encontrado SDK proprietário incorporado. A função principal é local e o rótulo final depende da interpretação do revisor.

## Compatível

- Somente `google()`, `mavenCentral()` e `gradlePluginPortal()` são usados na resolução. Não há JitPack, repositório privado, GitHub Packages ou URL Maven customizada.
- O Google Maven é usado para AndroidX/SDK de build; isso não implica Google Play Services.
- Não foi encontrado Firebase, Crashlytics, Google Analytics, AdMob, Google Mobile Ads, Google Maps SDK, FusedLocationProvider, Google Sign-In, App Check, Firebase Messaging, Remote Config, Performance Monitoring, ML Kit proprietário, Facebook/Meta SDK, Adjust, AppsFlyer, Branch ou Sentry.
- Não há `google-services.json`, chave de API, token, senha, chave privada, keystore ou configuração de assinatura no fonte revisado.
- Não há dependências com `+` ou `SNAPSHOT`, versionamento por data, Git hash obrigatório, geração aleatória ou download de recursos no processo de build.
- O app usa APIs padrão de Android para câmera, localização/GNSS e sensores, sem dependência oculta de GApps. Isso preserva compatibilidade com AOSP, microG opcional e ROMs sem Google.
- O provisionamento do L-Shell Beacon usa somente BLE/GATT, bonding e permissões padrão do Android; a descoberta LAN usa `NsdManager`/mDNS e HTTP local. Não foi adicionada Nearby Connections API, Play Services, Firebase ou dependência proprietária.
- O catálogo CelesTrak é opcional, tem cache válido por seis horas, fallback de URL, uso de cache antigo em falha e retry manual. Sua indisponibilidade afeta apenas o catálogo/AR, não o monitoramento local.
- O acesso Starlink é local: Dishy em `192.168.100.1:9200` e roteador em `192.168.1.1:9000`. Não foi encontrado login, OAuth, cookie, token de conta, faturamento ou endpoint cloud Starlink.
- O CelesTrak usa `HttpURLConnection`/HTTPS com o trust store padrão do Android. Não existe `TrustManager` permissivo, bypass SSL, CA adicional ou validação de hostname desativada.
- O acesso HTTPS ao CelesTrak foi validado manualmente pelo mantenedor em CRDroid Vanilla sem GApps após a remoção da CA customizada.
- Câmera, sensores, localização e canais gRPC possuem rotinas de descarte/fechamento no ciclo de vida revisado.
- `isMinifyEnabled = false` e `isShrinkResources = false` permanecem inalterados.

## Dependências

Versões principais declaradas diretamente:

| Dependência | Versão | Função | Licença | Classificação |
|---|---:|---|---|---|
| Android Gradle Plugin | 8.13.2 | Build Android | Apache-2.0 | OK para F-Droid |
| Kotlin Gradle plugin/runtime | 2.1.10 | Linguagem/build | Apache-2.0 | OK para F-Droid |
| AndroidX Core KTX | 1.15.0 | APIs Android/Kotlin | Apache-2.0 | OK para F-Droid |
| AndroidX Activity Compose | 1.10.0 | Host Compose | Apache-2.0 | OK para F-Droid |
| AndroidX Lifecycle | 2.8.7 (com módulos transitivos resolvidos em versão compatível) | Lifecycle e ViewModel | Apache-2.0 | OK para F-Droid |
| Compose BOM | 2025.02.00 | Alinhamento de versões Compose | Apache-2.0 | OK para F-Droid |
| Compose UI/Foundation/Animation | via BOM | Interface e animações | Apache-2.0 | OK para F-Droid |
| Material 3 | 1.4.0 | Componentes Material | Apache-2.0 | OK para F-Droid |
| Navigation Compose | 2.8.7 | Navegação | Apache-2.0 | OK para F-Droid |
| WorkManager | 2.10.0 | Trabalho agendado | Apache-2.0 | OK para F-Droid |
| DataStore Preferences | 1.1.2 | Preferências locais | Apache-2.0 | OK para F-Droid |
| Jetpack Glance | 1.1.1 | Widgets | Apache-2.0 | OK para F-Droid |
| CameraX | 1.3.4 | Prévia AR | Apache-2.0 | OK para F-Droid |
| kotlinx.coroutines | 1.10.1 | Concorrência | Apache-2.0 | OK para F-Droid |
| Protocol Buffers Java/Kotlin lite | 4.29.3 | Mensagens gRPC | BSD-3-Clause | OK para F-Droid |
| Protobuf Gradle plugin | 0.9.4 | Geração de bindings | Apache-2.0 | OK para F-Droid |
| gRPC Java | 1.70.0 | Transporte local | Apache-2.0 | OK para F-Droid |
| gRPC Kotlin | 1.4.1 | Stubs Kotlin/coroutines | Apache-2.0 | OK para F-Droid |
| predict4java | 1.2.2 | Propagação orbital | MIT | OK para F-Droid |
| `javax.annotation-api` | 1.3.2 | Anotações em compilação | CDDL-1.1 / GPL-2.0 com Classpath Exception | OK para F-Droid |
| JUnit | 4.13.2 | Testes locais | EPL-1.0 | OK para F-Droid |

Dependências transitivas observadas incluem Okio, Guava, Gson, Apache Commons Lang, SLF4J, PerfMark e artefatos auxiliares de anotações. São componentes open source com licenças permissivas ou compatíveis; devem constar no inventário gerado da versão final. Nenhuma exige um serviço proprietário em runtime.

### Binários e blobs

| Arquivo/tipo | Origem | Estado |
|---|---|---|
| `gradle/wrapper/gradle-wrapper.jar` | Gradle 8.13, tag oficial `v8.13.0` | SHA-256 oficial confirmado: `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f` |
| `app/release/DishyDash-beta-1.0.1.apk` | Artefato antigo já compilado | Não incluir na fonte submetida |
| Testes de telemetria protobuf | `DishGetStatusResponse` construído com valores sintéticos explícitos em `TelemetryTest.kt` | Nenhuma captura binária ou telemetria real de equipamento é incluída |
| `.so` vistos somente sob `app/build/` | Artefatos gerados de CameraX/AndroidX | Não estão incorporados como blobs versionados; serão reconstruídos/resolvidos pelo build |

Não foram encontrados `.aar`, `.jar` adicionais em `libs/`, executáveis próprios ou bibliotecas nativas pré-compiladas no fonte da aplicação.

## Privacidade

Fluxo principal:

```text
Dishy/roteador local -> L-Shell Orbit -> SQLite/preferências locais
CelesTrak -> L-Shell Orbit AR -> cache local
Dishy local -> L-Shell Beacon opcional -> armazenamento local do Beacon -> L-Shell Orbit
```

Não foi encontrado fluxo de métricas, localização, identificadores, relatórios ou dados da rede do L-Shell Orbit para terceiros. A requisição CelesTrak revela somente os metadados normais de uma conexão web; o código não anexa GPS, métricas da antena ou identificador persistente.

O histórico local usa `dish_history.db`, com limpeza de amostras antigas implementada, e as configurações/cursors ficam em preferências privadas. Os elementos orbitais ficam no cache. `android:allowBackup="false"` reduz a cópia externa dos dados do app.

Permissões declaradas e finalidade:

| Permissão | Finalidade encontrada | Estado |
|---|---|---|
| `INTERNET` | gRPC local e HTTPS CelesTrak | Necessária |
| `ACCESS_NETWORK_STATE` | Estado/rede para monitoramento e componentes Android | Revisar uso direto, sem remover automaticamente |
| `ACCESS_WIFI_STATE` | Suporte à descoberta mDNS do Beacon na rede Wi-Fi atual | Necessária para a descoberta local |
| `CHANGE_NETWORK_STATE` | Pré-requisito do serviço `connectedDevice` em Android recente | Manter até validação manual |
| `CHANGE_WIFI_MULTICAST_STATE` | Descoberta mDNS/DNS-SD opcional do L-Shell Beacon na LAN | Necessária para descoberta local confiável em dispositivos compatíveis |
| `CAMERA` | Prévia do modo AR | Necessária para a função; câmera declarada opcional |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | GNSS/localização Android para AR e calibração | Necessária para essas funções; sem Google Location Services |
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | Descoberta e provisionamento local opcional do L-Shell Beacon no Android 12+ | Necessárias somente ao configurar o Beacon; APIs padrão Android |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` (até API 30) | Compatibilidade BLE do Beacon em versões antigas do Android | Limitadas por `maxSdkVersion=30` |
| `POST_NOTIFICATIONS` | Alertas, monitoramento e relatórios | Necessária no Android 13+ quando o usuário habilita essas funções |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Monitoramento local persistente e visível | Necessária |
| `RECEIVE_BOOT_COMPLETED` | Restaurar agendamentos locais após reinício/atualização | Necessária para o comportamento existente |

Foi criado `PRIVACY.md` para tornar este comportamento auditável ao usuário.

### L-Shell Beacon Protocol v1 e firmware 0.1.0

- A descoberta anuncia/procura somente o tipo `_lshell-beacon._tcp` por mDNS/DNS-SD; não há varredura agressiva de subnet.
- O setup inicial usa BLE Secure Connections/bonding e uma janela autenticada com tempo limitado. Um Beacon sem configuração abre o setup automaticamente; em um Beacon já configurado, manter BOOT pressionado por cerca de cinco segundos reabre a sessão. SSID/senha são características somente de escrita; o Android não persiste a senha e o firmware a grava em NVS sem expô-la em endpoints ou logs.
- O protocolo público v1 está documentado em `docs/BEACON_PROTOCOL.md`; o firmware inicial fica em `firmware/lshell-beacon/` e é coberto por `GPL-3.0-or-later`.
- A operação LAN de histórico ainda não possui autenticação criptográfica final. Ela funciona em HTTP somente na LAN local, sem expor credenciais; o projeto não usa chave fixa nem criptografia própria.
- O contrato futuro de autenticação LAN exige chave exclusiva por pareamento, troca moderna de chaves, KDF, AEAD e proteção contra replay. A confirmação curta por botão não faz parte do provisionamento BLE atual.
- A sincronização implementada é incremental por `beacon_id + sequence`, confirma o maior registro salvo somente após a transação e usa índice único para retries idempotentes.
- Amostras do Beacon entram em `dish_history.db`, com `source = BEACON`; dados locais válidos dentro da mesma janela temporal são preservados para evitar duplicação.
- O firmware usa Arduino ESP32 core (LGPL-2.1) e o projeto PlatformIO Espressif32 (Apache-2.0); componentes ESP-IDF retêm suas licenças upstream. Não há SDK proprietário ou serviço cloud.
- A compatibilidade com F-Droid/AOSP permanece, sujeita à validação manual do app e do firmware em hardware.

## Build

- `compileSdk`/`targetSdk` 35, `minSdk` 26 e JDK 17 estão claramente definidos.
- Todas as versões diretas são fixas; não há `+` ou snapshots.
- O build gera stubs gRPC/Protobuf a partir do `.proto` incluído, portanto os fontes gerados não precisam ser versionados.
- Não há recurso mutável baixado durante a compilação. CelesTrak é acessado somente em runtime.
- `distributionUrl` permanece `https://services.gradle.org/distributions/gradle-8.13-bin.zip`.
- `distributionSha256Sum` corresponde ao valor oficial da distribuição 8.13: `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.
- O JAR anterior tinha SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`, hash publicado para wrappers de versões Gradle posteriores, e não correspondia ao 8.13 configurado.
- O JAR substituto foi obtido diretamente do tag oficial `gradle/gradle` `v8.13.0` e confere com o checksum oficial do Gradle 8.13: `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`.
- `gradlew` e `gradlew.bat` também foram alinhados aos scripts do mesmo tag oficial. Seus hashes são, respectivamente, `c76f16038b4e70b84ed0b3b9e08e82a8ecaf66ec0efdfdecdece92520fa3cecb` e `0f3ed8f03b50934cb8c48b15a470d5c20a30a5385825e48b55bcc8ea3d8f8e18`.
- A inspeção dos scripts não encontrou downloads adicionais, comandos externos ou comportamento inserido pelo projeto. Eles apenas localizam Java e iniciam `org.gradle.wrapper.GradleWrapperMain` pelo JAR local.
- A assinatura de release não está embutida, o que é correto. O F-Droid assinará seu próprio pacote; builds locais de release devem usar chave privada fora do repositório.
- Em 2026-09-26, `gradlew.bat test` foi iniciado com JDK 17 e JDK 21. Configuração, geração protobuf e compilação Kotlin avançaram, sem erro de fonte, mas o `javac` falhou antes dos testes com `AccessDeniedException` ao fechar qualquer JAR via ZipFS neste sandbox Windows. Um programa Java mínimo reproduziu a falha até com um JAR copiado para o workspace, confirmando bloqueio ambiental. Como `test` não terminou, `clean assembleDebug` e `assembleRelease` não foram executados. Repita os três comandos em um terminal local normal antes da publicação.

## Licenciamento

O L-Shell Orbit está licenciado sob **GNU GPL v3 ou posterior**, identificador SPDX `GPL-3.0-or-later`. O texto integral está em `LICENSE`; README e rascunho de metadata usam o mesmo identificador.

As dependências e os recursos externos continuam sob suas próprias licenças, registradas em `THIRD_PARTY_NOTICES.md`. A licença do projeto não é apresentada como licença do protocolo Starlink, dos nomes técnicos de interoperabilidade ou das marcas de terceiros. A documentação conservadora de `device.proto` permanece em `docs/DEVICE_PROTO_PROVENANCE.md`.

Não foi criada uma tela nova de licenças nesta etapa. A tela de créditos existente pode continuar como apresentação ao usuário, mas não substitui os avisos legais distribuídos com a fonte.

## F-Droid

Próximas etapas concretas:

1. Preservar a documentação e, se disponível, anexar o histórico original que sustenta a síntese de `app/src/main/proto/device.proto`.
2. Inicializar/publicar um repositório público com histórico limpo, sem APK, páginas baixadas, build output, IDE, segredos ou chaves.
3. Ao criar o repositório público, confirmar que `gradlew` foi versionado com permissão executável (`100755`), já que essa informação não pode ser validada no índice Git desta cópia local no Windows.
4. Fazer uma build limpa manual e validar o APK resultante.
5. Publicar o fonte da beta com uma tag coerente, como `v0.1.0-beta`.
6. Criar URL pública de issues e changelog e completar `docs/fdroid/io.github.strongsand.lshell.yml.example`.
7. Revisar os textos já preparados em `fastlane/metadata/android/` e adicionar screenshots reais. Os idiomas atuais são `en-US` e `pt-BR`.
8. Submeter a inclusão pelo processo oficial e responder a eventuais pedidos de Anti-Features/proveniência.

### Anti-Features

- **Ads:** não se aplica.
- **Tracking:** não se aplica.
- **NonFreeDep:** não identificado.
- **NonFreeNet:** não é obrigatório pelo código auditado. O catálogo CelesTrak é opcional e o núcleo local funciona sem ele; a interoperabilidade com hardware/ecossistema proprietário pode, contudo, ser avaliada pelo revisor.
- **UpstreamNonFree:** não identificado, desde que o L-Shell Orbit seja publicado como projeto original sob licença livre.
- **NonFreeAssets:** nenhum conhecido. A CA pública anteriormente embarcada foi removida, e a documentação do schema foi adicionada.

Foi criado um rascunho de metadata em `docs/fdroid/io.github.strongsand.lshell.yml.example`. A estrutura da receita, versão e tag futura estão preenchidas; URLs permanecem como pendências comentadas até existirem repositório, tracker e changelog públicos. `fastlane/metadata/android/` contém textos `en-US` e `pt-BR`; screenshots reais, ícone PNG/feature graphic e revisão final de loja continuam pendentes.

## Arquivos alterados

- `.gitignore`: passou a excluir builds, APK/AAB, saída de IDE/ferramentas, páginas de referência baixadas, material de assinatura e arquivos comuns de segredos.
- `gradle/wrapper/gradle-wrapper.properties`: recebeu `distributionSha256Sum` para a distribuição Gradle 8.13.
- `analytics.settings`: removido por ser configuração local da ferramenta com identificador pseudônimo, sem função no app ou no build.
- `README.md`: criado com funcionamento, requisitos, privacidade, build, limitações, disclaimer e estado de licenciamento.
- `PRIVACY.md`: criado com fluxos de dados, armazenamento e justificativa de permissões.
- `THIRD_PARTY_NOTICES.md`: criado com inventário inicial de terceiros, dados externos e pendências de proveniência.
- `docs/F-DROID-AUDIT.md`: criado com esta auditoria e roteiro de submissão.
- `app/build.gradle.kts`: identidade técnica consolidada em `io.github.strongsand.lshell` e versão ajustada para `0.1.0-beta`, mantendo `versionCode = 1`.
- `app/src/main/AndroidManifest.xml`, fontes Kotlin e testes: packages, imports, componentes e actions migrados para `io.github.strongsand.lshell`.
- `app/src/main/proto/device.proto`: `java_package` migrado e cabeçalho de procedência conservador adicionado.
- `app/src/main/res/values/widget_strings.xml`, notificações e textos visíveis: branding atualizado para L-Shell Orbit.
- `docs/DEVICE_PROTO_PROVENANCE.md`: criado para separar fatos observáveis, histórico informado, referência comunitária e pontos não verificados.
- `LICENSE`: texto integral da GNU GPL v3, aplicado ao projeto como `GPL-3.0-or-later`.
- `app/src/main/AndroidManifest.xml`: removida a referência à configuração de CA customizada.
- `app/src/main/res/xml/network_security_config.xml`: removido porque só acrescentava a CA Sectigo ao trust store do CelesTrak.
- `app/src/main/res/raw/sectigo_root_r46.crt`: removido após confirmar que não tinha outro consumidor.
- `docs/fdroid/io.github.strongsand.lshell.yml.example`: rascunho de metadata com licença e versão conhecidas, sem inventar URLs.
- `gradle/wrapper/gradle-wrapper.jar`: substituído pelo JAR oficial do Gradle 8.13 após validação do checksum publicado.
- `gradlew` e `gradlew.bat`: alinhados aos scripts oficiais do tag `v8.13.0`, sem execução.
- Branding visível, README, privacidade, avisos de terceiros, procedência e metadata: nome público atualizado de L-Shell para L-Shell Orbit, preservando `io.github.strongsand.lshell`.
- `app/src/main/java/io/github/strongsand/lshell/beacon/`: implementação Android do L-Shell Beacon com provisionamento BLE protegido, descoberta mDNS, status LAN e sincronização incremental idempotente do histórico local.
- `app/src/main/java/io/github/strongsand/lshell/BeaconScreen.kt` e navegação/Home: onboarding, estados de descoberta e pareamento BLE, painel, configurações e card condicional Material 3.
- `HistoryStore.kt` e consumidores de histórico: schema local registra origem, ID e sequência do Beacon, com deduplicação e integração aos gráficos, relatórios e diagnósticos existentes.
- `app/src/main/res/values*/beacon_strings.xml`: textos do Beacon em português, inglês e espanhol.
- `app/src/main/AndroidManifest.xml`: permissões de estado Wi-Fi e multicast adicionadas para a descoberta mDNS local, sem SDK proprietário.
- `docs/BEACON_PROTOCOL.md`: contrato Beacon Protocol v1 para provisionamento BLE, descoberta LAN, endpoints, estados, erros e compatibilidade.
- `firmware/lshell-beacon/`: firmware `0.1.0` para ESP32 clássico, com provisionamento BLE autenticado em janela limitada, NVS, Wi-Fi, mDNS, HTTP, identidade, LED, botão de recovery/reset, coleta local da Dishy, armazenamento circular e API de sincronização.
- `app/src/main/AndroidManifest.xml` e implementação Beacon: permissões BLE por versão do Android, provisionamento GATT protegido e transição BLE → mDNS/HTTP adicionados sem SDK proprietário.
