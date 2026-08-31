# 02 — Visão de Engenharia

Data: 2026-08-31 · Perspectiva: Engenheiro de Software

---

## 1. Inventário verificado

### Backend — `ideiasboxsa-brasil-drama-backend` @ `9f70446`

```
70 arquivos .java em src/main          7.877 linhas
38 arquivos .java em src/test          2.251 linhas
44 classes @RestController            136 mapeamentos HTTP
36 changelogs Liquibase
Spring Boot 3.3.13 · Java 21 · PostgreSQL 16 · AWS SDK 2.32.29
```

Razão teste/produção: **0,29**. Baixa, mas o número engana em duas direções. Os testes existentes são
*contract tests* de integração (sobem contexto Spring e batem em endpoint real), que cobrem mais por linha
que teste unitário. Por outro lado, cinco pacotes inteiros não têm nenhum.

### Android — `brasil-drama-android` @ `f054a2b`

```
95 arquivos .kt em src/main           11.414 linhas
33 arquivos .kt em test/androidTest    1.507 linhas
13 telas Compose
59 endpoints Retrofit em 4 interfaces
compileSdk 36 · minSdk 26 · Compose BOM 2025.10.01 · Media3 1.8.0
```

Razão teste/produção: **0,13**. É a frente com mais código, mais superfície de usuário, e menos teste.

### Studio — `brasil-drama-admin` @ `be38765`

```
44 arquivos .ts/.tsx                   4.933 linhas
29 rotas
0 testes
Next.js 15.5 · React 19.1 · TanStack Query 5 · Zod 4
```

Razão teste/produção: **0,00**.

---

## 2. Estado da integração contínua

| Frente | Gatilho | O que roda | Lacuna |
|---|---|---|---|
| Backend | push + PR em `develop`/`main` | `mvn -B verify` com PostgreSQL 16 real em service container | Sem cobertura medida, sem análise estática |
| Studio | push + PR em `develop`/`main` | `npm install` → `typecheck` → `build` | **Nenhum teste.** `npm install` sem lockfile |
| Android | **só `workflow_dispatch` ou push em `release/**`** | testes unitários DEV, compila instrumentação, gera APK | **Nenhum CI em `develop`** |

### O achado mais sério desta seção

**O Android não tem integração contínua.** `android-apk.yml` responde a acionamento manual e a push em
`release/**`. Como todo o trabalho acontece em `develop`, os testes unitários do app **não rodam
automaticamente em commit algum**.

Consequência prática: das 11.414 linhas de Kotlin, nenhuma é validada por padrão quando alguém envia código.
O time descobre quebra quando alguém pede um APK. Isso inverte a proteção: a frente que mais precisa de rede
(código que vira artefato assinado e imutável na loja) é a que menos tem.

**Correção: 20 minutos.** Adicionar um job `on: push: branches: [develop]` que roda
`./gradlew testDevDebugUnitTest`. É o melhor retorno por minuto investido em todo este dossiê.

### Estado do build local

| | `gradlew` | `mvnw` | `node_modules` | Cache |
|---|---|---|---|---|
| Android | ❌ | — | — | `~/.gradle` inexistente |
| Backend | — | ❌ | — | `~/.m2` vazio |
| Studio | — | — | ❌ | — |

Nenhuma das três frentes compila em máquina limpa. Toda verificação passa pelo GitHub Actions.

O CI do Android contorna isso gerando o wrapper em tempo de execução:
```yaml
- name: Create Gradle Wrapper
  run: gradle wrapper --gradle-version 8.13
```
Funciona, mas significa que **a versão do Gradle não está sob controle de versão** — ela é decidida por uma
linha de workflow, e ninguém consegue reproduzir localmente a mesma build que o CI produziu.

### O custo mensurável do ciclo quebrado

Os repositórios contêm **176 arquivos `RC2_E06_*.md`**. Amostra real de nomes e tamanhos:

```
RC2_E06_CI_NOW.md              23 bytes
RC2_E06_CI_PLEASE.md           14 bytes
RC2_E06_CI_GO_NOW.md           23 bytes
RC2_E06_AWAIT_CI.md            87 bytes
RC2_E06_ABSOLUTE_END.md        20 bytes
RC2_E06_CHECK.md               13 bytes
```

São commits vazios criados para disparar pipeline, porque não havia como testar antes de empurrar. Não é
desleixo — é uma adaptação racional a uma restrição de ferramenta. Consertar a ferramenta faz o sintoma
desaparecer sozinho; limpar os arquivos sem consertar a ferramenta só reinicia a contagem.

---

## 3. Defeitos concretos encontrados

Ordenados por severidade. Cada um com evidência e caminho de correção.

### 🔴 D-01 — `restore()` de compras perde a transação por auto-invocação

`GooglePurchaseApi.java:273-282`

```java
GoogleRestoreResponse restore(UUID userId, GoogleRestoreRequest request) {   // sem @Transactional
    for (GooglePurchaseVerifyRequest purchase : request.purchases()) {
        var result = verify(userId, purchase);   // @Transactional — mas chamado via this
```

`verify()` é `@Transactional`; `restore()` não é e o chama diretamente. O proxy do Spring só intercepta
chamadas que entram pelo bean, não chamadas internas — então **a anotação não tem efeito nesse caminho**.

Impacto: a restauração aceita até 100 compras por requisição (`@Size(max = 100)`). Se falhar na de número 47,
as 46 primeiras já gravaram recibo e creditaram carteira, e as 53 restantes não. Estado parcial, sem
compensação. O `creditOnce` idempotente limita o dano a "crédito faltante", não "crédito duplicado" — o que
é o lado certo de errar, mas ainda é um usuário que pagou e não recebeu.

**Correção:** extrair `verify` para um bean separado, ou anotar `restore` e deixar `verify` com
`Propagation.REQUIRED`. ~1 hora.

### 🔴 D-02 — `usesCleartextTraffic="true"` no app inteiro

`AndroidManifest.xml:13`

Permite HTTP em texto claro para qualquer destino, em todos os flavors, inclusive produção. Combinado com
`allowBackup="true"` na linha 11 — que envia os dados do app, incluindo o que estiver no DataStore, para o
backup do Google Drive do usuário.

Impacto: tráfego interceptável em rede hostil; token de sessão potencialmente replicado para fora do
dispositivo. A Google Play sinaliza ambos na revisão de segurança.

**Correção:** remover `usesCleartextTraffic`, ou restringi-lo ao flavor `dev` via `network_security_config.xml`.
Definir `allowBackup="false"` ou fornecer regras de backup que excluam credenciais. ~2 horas.

### 🟠 D-03 — Sem controle de taxa em nenhum endpoint público

Nenhuma ocorrência de rate limit, throttling ou bucket em `src/main`.

Endpoints abertos e exploráveis:

| Endpoint | Exploração |
|---|---|
| `POST /v1/auth/login` | Força bruta e preenchimento de credenciais, sem limite |
| `POST /v1/auth/register` | Criação em massa de contas → cada uma leva bônus de boas-vindas de 100 moedas |
| `POST /v1/analytics/playback/events` | Ingestão anônima ilimitada: polui métrica de produto e infla a tabela |
| `POST /v1/auth/password/forgot` | Amplificação de e-mail via Mailgun, com custo por mensagem |

O caso do registro é o mais caro: `REWARDS_WELCOME_BONUS` credita 100 moedas por conta nova
(`application-dev-cloud.yml`). Sem limite de taxa nem verificação de e-mail, criar 10.000 contas é um
script de dez linhas e um milhão de moedas emitidas.

**Correção:** filtro de rate limit por IP nos caminhos `/v1/auth/**` e no ingest de telemetria; verificação
de e-mail antes de conceder bônus. ~1 dia.

### 🟠 D-04 — Confirmação de compra depende de o usuário voltar ao paywall

`PaywallScreen.kt:67-107`

O fluxo é correto: valida no servidor → depois `consume()` (pacote de moedas) ou `acknowledge()` (assinatura).
Mas se a chamada de confirmação falhar, a única resposta é uma mensagem:

```kotlin
"Compra validada; a finalização na Google Play será tentada novamente."
```

Não há retentativa. "Tentada novamente" acontece apenas se o usuário reabrir o paywall e o
`restorePurchases()` reprocessar. **A Google Play reembolsa automaticamente compras não confirmadas em 3 dias.**

O servidor não serve de rede de proteção: ele lê `acknowledgementState` da API do Google
(`GooglePurchaseApi.java:139`) mas nunca chama o endpoint de acknowledge.

Impacto: perda direta de receita, em volume proporcional à taxa de falha da rede no momento da compra —
justamente o cenário mais provável em rede móvel brasileira.

**Correção:** confirmação no servidor logo após a validação, tornando o cliente redundante em vez de único
responsável. ~4 horas.

### 🟠 D-05 — Segredo JWT com valor padrão embutido no código

`JwtService.java:19`

```java
@Value("${security.jwt.secret:${JWT_SECRET:dev-only-change-this-secret-32-bytes}}")
```

36 bytes — passa na validação de comprimento da linha seguinte. O profile `dev-cloud` exige `${JWT_SECRET}`
sem default e falha rápido, o que está certo. Mas o profile base aceita o valor embutido.

Qualquer implantação que suba sem `SPRING_PROFILES_ACTIVE` correto usa uma chave de assinatura que está
publicada neste repositório. Com ela, forja-se token de qualquer usuário — inclusive administrador.

**Correção:** remover o default; falhar na inicialização se `JWT_SECRET` não estiver definido, em todos os
profiles. ~15 minutos.

### 🟡 D-06 — Duas classes órfãs com quatro arquivos de teste apontados para elas

| Classe | Referências em `main` | Referências em `test` |
|---|---|---|
| `AutomaticEpisodeAdvance` | 1 (só a própria definição) | 1 |
| `BingeAdvanceCoordinator` | 1 (só a própria definição) | 3 |

A integração real do modo maratona foi feita inline no `CinematicPlayerScreen.kt`. As classes que foram
projetadas para conter essa lógica ficaram sem uso.

Resultado: quatro arquivos de teste protegem código que nunca executa, e o código que executa não tem
cobertura unitária. É uma cobertura que dá falsa segurança — pior que nenhuma, porque parece que existe.

**Correção:** usar as classes na integração (preferível — tira regra de negócio do Composable) ou removê-las
e reapontar os testes. ~4 horas. Já é o item F1.1 do plano RC1.

### 🟡 D-07 — Studio sem lockfile

`package-lock.json` não existe e não está versionado. O CI roda `npm install`, não `npm ci`.

Cada build resolve dependências de novo. Duas builds do mesmo commit podem produzir artefatos diferentes.
Uma versão menor comprometida de qualquer dependência transitiva entra sozinha no build de produção.

**Correção:** gerar e commitar o lockfile, trocar para `npm ci`. ~30 minutos.

### 🟡 D-08 — AdMob com identificador de teste do Google

`AndroidManifest.xml:33`

```xml
android:value="ca-app-pub-3940256099942544~3347511713"
```

É o App ID público de teste do Google. Em produção, serve anúncios de teste — **receita de anúncio igual a
zero**, silenciosamente. Correto para desenvolvimento, bloqueador para lançamento.

Além disso, `ADMOB_REWARDED_AD_UNIT_ID` tem default vazio no backend.

**Correção:** ID real por flavor, vindo de propriedade Gradle/Secret. ~1 hora, depois que a conta AdMob existir.

### 🟡 D-09 — Nenhum `rollback` definido em 36 changelogs Liquibase

Uma migração ruim em produção não tem caminho de volta automatizado. Hoje é irrelevante (não há produção).
No dia em que houver, será o item mais sentido desta lista.

**Correção:** definir `rollback` nos changesets de alteração estrutural daqui para a frente; não é necessário
retroagir nos 36 existentes antes do primeiro deploy real. ~2 horas de política + esforço incremental.

---

## 4. Qualidade que merece registro

Um relatório só de problemas distorce. O que está bem feito:

**Zero `TODO`, `FIXME`, `XXX` ou `HACK` nas três frentes.** Verificado por varredura em todo `src/main`.
Em 24 mil linhas, isso não acontece por acaso.

**Idempotência onde importa.** `wallet.creditOnce(userId, "google-play:" + tokenHash, ...)`
(`GooglePurchaseApi.java:268`) usa o hash do token como chave. Reprocessar a mesma compra não credita duas vezes.

**Verificação criptográfica real do SSV do AdMob.** `RewardedAdApi.java:265-269` verifica assinatura ECDSA
contra o conjunto de chaves do Google, com atualização de chave em cache miss. Muitos projetos aceitam o
callback sem verificar — este não.

**Recibo de compra com hash, não com token em claro.** `sha256(request.purchaseToken())` como chave primária
(`GooglePurchaseApi.java:229`). O token bruto nunca é persistido.

**Vinculação de token a usuário.** Se o mesmo token de compra aparecer para outro usuário, retorna `409`
(`GooglePurchaseApi.java:233`). Fecha a porta para compartilhamento de recibo.

**Validação de mídia no servidor, não no cliente.** `MediaStorageService` faz `headObject` depois do upload
e valida tipo e tamanho reais (`:60-66`) em vez de confiar no que o navegador declarou.

**Bootstrap de administrador desligado por padrão**, exigindo senha de 12+ caracteres e falhando rápido se
mal configurado (`AdminBootstrap.java:44-46`).

**Pipeline de deploy do Studio com verificação pós-deploy rota a rota**, incluindo os assets JS de cada
página. É rigoroso de um jeito que as outras duas frentes deveriam copiar.

**Contextos Liquibase corretamente separados** — o seed de desenvolvimento está marcado `context: dev`
(`db.changelog-master.yaml:108`) e não contamina produção.

---

## 5. Cobertura de teste, por domínio

### Backend

| Domínio | Testes | Avaliação |
|---|---|---|
| `admin` | 15 | Excessivo — 12 dos 15 são variações de auditoria |
| `rewards` | 8 | Bom |
| `catalog` | 5 | Bom |
| `analytics` | 4 | Adequado |
| `monetization` | 2 | **Insuficiente para onde há dinheiro** |
| `auth`, `library`, `wallet` | 1 cada | Insuficiente |
| `media`, `identity`, `push`, `recommendation`, `home` | **0** | Descoberto |

A distribuição está invertida em relação ao risco. 15 testes para a trilha de auditoria administrativa
(que não move dinheiro, não toca mídia e não afeta o usuário final) e 2 para monetização.

### Android

33 arquivos de teste, dos quais 4 apontam para código morto (D-06). Concentrados em lógica de maratona e
política de acesso. **Nenhum teste de tela, nenhum teste de ViewModel, nenhum teste de rede.**

### Studio

Nenhum. Existe um commit removendo um teste Vitest por incompatibilidade com o typecheck (`c2fb341`) —
houve uma tentativa que foi abandonada em vez de resolvida.

---

## 6. Laboratório de dispositivos — construído e desligado

`mobile-center/` contém uma infraestrutura de teste em dispositivo genuinamente boa:

- Docker + emuladores Android, matriz API 32/33/34 (35 opcional)
- Appium + WebdriverIO, relatórios JUnit, capturas de tela, logs por dispositivo
- Inspeção manual via noVNC
- Reutiliza o APK do build verde em vez de recompilar — rastreabilidade por branch e SHA

**Está desativado**: o job exige `vars.MOBILE_CENTER_ENABLED == 'true'` e um runner auto-hospedado com
`/dev/kvm`.

E a suíte atual testa apenas *"o app abre e não fecha sozinho"* (`runner/specs/smoke.spec.js`).

Há trabalho de qualidade parado aqui. O plano RC1 exige homologação em aparelho físico para fechar F1.2 e
F2.1 — este laboratório é exatamente a ferramenta para isso, e já está construído. Ligá-lo e escrever quatro
ou cinco cenários reais (login, player, paywall, favoritos) converteria um checklist manual assinado em
evidência automatizada e repetível.

---

## 7. Ordem de correção recomendada

### Semana 1 — destravar (nada aqui é feature)

| # | Item | Esforço |
|---|---|---|
| 1 | CI do Android em `develop` | 20 min |
| 2 | `gradlew` + `mvnw` versionados; `.gitignore` no Android | 3 h |
| 3 | `package-lock.json` + `npm ci` (D-07) | 30 min |
| 4 | Remover default do `JWT_SECRET` (D-05) | 15 min |
| 5 | Seed DEV com ≥5 episódios e vídeo real (F0.4 do plano) | 1 dia |

### Semana 2 — corrigir o que quebra em produção

| # | Item | Esforço |
|---|---|---|
| 6 | Transação do `restore()` (D-01) | 1 h |
| 7 | Confirmação de compra no servidor (D-04) | 4 h |
| 8 | Rate limit em `/v1/auth/**` e telemetria (D-03) | 1 dia |
| 9 | Remover `usesCleartextTraffic` e `allowBackup` (D-02) | 2 h |
| 10 | Reconciliar classes órfãs do player (D-06) | 4 h |

### Semana 3 — fechar as lacunas de confiança

| # | Item | Esforço |
|---|---|---|
| 11 | Testes de `MediaStorageService` e `VisitorMergeService` | 1 dia |
| 12 | Contract test OpenAPI Android↔Backend | 2 dias |
| 13 | Ligar o Mobile Center com 5 cenários reais | 2 dias |
| 14 | Primeiros testes do Studio (guard de auth, cliente de API) | 1 dia |

---

## 8. Veredito de engenharia

O código é **melhor do que os processos que o cercam**.

As decisões difíceis — idempotência de pagamento, verificação criptográfica de recompensa, autoridade no
servidor, separação de contexto de migração — foram tomadas corretamente. Os defeitos encontrados são
localizados e todos corrigíveis em horas, com uma única exceção (rate limiting, ~1 dia).

O que está fora do padrão do código é a **infraestrutura de verificação**: uma frente sem CI, uma frente sem
testes, três frentes sem build local, e um laboratório de dispositivos construído e desligado.

Isso é uma boa notícia disfarçada de má. Corrigir processo é mais rápido e mais previsível que corrigir
arquitetura — e não há arquitetura para corrigir aqui.
