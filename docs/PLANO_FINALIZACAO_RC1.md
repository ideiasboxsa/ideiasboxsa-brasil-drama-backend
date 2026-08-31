# Plano de Finalização — Brasil Drama RC1

Documento mestre das três frentes. Criado: 2026-08-30.
Cópias-ponteiro em `brasil-drama-android/docs/` e `brasil-drama-admin/docs/`.

Status: **PROPOSTA — aguardando aprovação do product owner.**

---

## 1. Diagnóstico que motiva este plano

Levantamento feito em 2026-08-30 sobre os três repositórios e sobre o ambiente DEV.

### 1.1 O que já está pronto e não estava registrado

E06 (Modo Maratona) foi integrado ao player no commit `f73c068`, apesar de `RC2_E06_PROGRESS.md` ainda declarar a integração como pendente.

Evidência em `brasil-drama-android`:

| Requisito do `RC2_PLAYER_PATCH_PLAN.md` | Situação |
|---|---|
| Ponto de integração em `Player.STATE_ENDED` | Feito — `CinematicPlayerScreen.kt:271` |
| Guarda contra dupla emissão | Feito — `completionHandled`, `:257` |
| Tracker no escopo da série, não do episódio | Feito — `remember(drama.id)`, `:60` |
| `completion` → `next_episode` → `binge_session` uma única vez | Feito — `:164-170` |
| Reset em navegação manual | Feito — `manualEpisodeNavigation` |
| Nenhum asset alterado | Respeitado |

Conclusão: **os documentos de progresso estão atrás do código.** Isso é a primeira coisa a corrigir, porque invalida qualquer decisão tomada a partir deles.

### 1.2 Bloqueadores reais

**B1 — DEV não sustenta o gate que deveria provar.** API `UP`, catálogo servindo 200, porém:

| Drama | Episódios | Grátis |
|---|---|---|
| Contrato do Destino | 2 | 1 |
| Ela Voltou Dona de Tudo | 1 | 1 |
| Segredo de Família | 1 | 1 |

- Vídeos apontam para `storage.googleapis.com/gtv-videos-bucket/sample/...` — placeholders públicos do Google. Posters são S3 presigned real, logo o pipeline de **imagem** está validado e o de **vídeo** não.
- Limiar de maratona é 3 avanços consecutivos (`BingeSessionTracker.kt:4`). O drama mais longo tem 2 episódios = **1 avanço possível**. O evento `binge_session` é fisicamente inatingível em DEV.

Consequência: E06 não pode ser homologado, o D2 de conteúdo não pode ser declarado fechado, e `/analytics/binge` no Studio exibirá zero de forma indistinguível de defeito.

**B2 — Nenhuma das três frentes compila localmente.** Sem `gradlew`, sem `mvnw`, `~/.m2` vazio, `~/.gradle` inexistente, `node_modules` ausente. Toda validação depende exclusivamente do GitHub Actions.

Esse é o custo oculto mais alto do projeto: explica os **176 arquivos `RC2_E06_*.md`** (`CI_NOW.md`, `CI_PLEASE.md`, `CI_GO_NOW.md`, `AWAIT_CI.md`, `ABSOLUTE_END.md` cujo corpo é a palavra "CI.") — commits existentes apenas para disparar pipeline, porque não havia como testar antes de empurrar.

**B3 — Calendário vencido.** D3 (30/08) e D4 (31/08) chegaram sem atualização dos docs de progresso, congelados em 28/08 com Android 78% / Studio 80%. RC1 estava marcado para 01/09.

### 1.3 Dívidas estruturais

**D1 — Cobertura de teste apontada para código morto.** Em `app/src/main`, excluindo testes:

```
AutomaticEpisodeAdvance  → 0 usos
BingeAdvanceCoordinator  → 0 usos
```

Ambas possuem testes (`AutomaticEpisodeAdvanceTest`, `BingeAdvanceCoordinatorTest`, `BingeAdvanceResetTest`, `BingeSignalTest`). A integração real inlineou a lógica no Composable. Resultado: quatro arquivos de teste garantindo código que não executa, e o código que executa sem cobertura unitária.

**D2 — Regra de negócio no cliente, contra o próprio contrato.** O limiar `3` existe apenas em `BingeSessionTracker.kt`. Não há configuração equivalente no backend. `RC2_E06_METRIC_THRESHOLD.md` confirma que o Studio não reconstrói o limiar. Portanto o limiar só muda com release de APK — o que contraria "não hardcodar regras econômicas/recomendação na UI" (`RC2_SHORT_DRAMA_UX_CONTRACT.md`) e "superfícies server-driven" (`RC2_PRODUCT_EXPERIENCE_CONTRACT.md`).

**D3 — Backend sem teste onde há dinheiro, mídia e identidade.** Existem testes em `admin`, `analytics`, `auth`, `catalog`, `library`, `monetization`, `rewards`, `wallet`. Não existem em `media`, `home`, `push`, `recommendation`, `identity`. `VisitorMergeService` (fusão de visitante anônimo em conta) e `MediaStorageService` (presign/multipart S3) são os dois pontos mais delicados sem rede de proteção.

**D4 — Higiene de repositório no Android.** Não há `.gitignore`. `ci/brasil-drama-dev-debug.keystore.b64` está versionado. É keystore de debug — risco baixo — mas contraria o princípio declarado "nenhum secret em Git", e a ausência de `.gitignore` é o problema mais concreto dos dois.

**D5 — Compatibilidade Android↔Backend garantida só por convenção.** O README do backend determina compatibilidade com `BrasilDramaApi.kt` (51 endpoints `/v1/**`). Nada automatizado verifica isso. Uma renomeação de campo passa no CI das duas frentes e quebra em runtime no APK já publicado.

---

## 2. Princípios do plano

1. **Nada de escopo novo até RC1.** Vale o congelamento já existente: asset freeze mantido, polish cosmético vai para pós-RC.
2. **Destravar antes de acelerar.** F0 existe porque F1 e F2 são inexecutáveis sem ele.
3. **Evidência, não afirmação.** Cada fase fecha com artefato verificável, no padrão já usado em `D2_E2E_GATE.md`.
4. **Documento de progresso deixa de ser escrito à mão.** Passa a ser saída de pipeline (ver M10).
5. **Uma frente nunca declara pronto por conta própria** quando o contrato envolve outra. Regra já vigente no RC2 Product Experience Contract.

---

## 3. Fases

### F0 — Destravamento (pré-requisito de tudo)

Sem F0 nenhuma outra fase pode ser validada. Nada aqui é feature; é infraestrutura de verificação.

**Escopo**

- F0.1 Commitar `gradlew` + `gradle/wrapper/` no Android e `mvnw` + `.mvn/wrapper/` no backend. Resolve B2.
- F0.2 Criar `.gitignore` no Android (`build/`, `.gradle/`, `local.properties`, `.idea/`, `*.keystore`, `*.jks`). Resolve D4 parcialmente.
- F0.3 Mover o keystore de debug para GitHub Secrets; remover o `.b64` do tracking. Decisão do PO: reescrever histórico ou apenas remover do HEAD.
- F0.4 Seed DEV: uma série com **≥ 5 episódios** (≥ 4 liberados em sequência) e **vídeo real em S3** via o fluxo já existente do Studio (`presign` → `multipart` → `complete`). Resolve B1.
- F0.5 Sincronizar os docs de progresso das três frentes com o código real; marcar E06 como integrado.

**Evidência para fechar F0**

- `./gradlew testDevDebugUnitTest` e `./mvnw -q test` executáveis em máquina limpa.
- `git ls-files` do Android sem artefato de build e sem keystore.
- `GET /v1/catalog/dramas/{id}` da série de seed retorna ≥ 5 episódios com `videoUrl` no domínio `brasil-drama-dev-media`.
- Nenhum `RELEASE_PROGRESS.md` com data anterior ao fechamento de F0.

**Fora de F0:** qualquer mudança de comportamento de produto.

---

### F1 — Fechar E06 (Modo Maratona)

O código está pronto. Falta homologar e corrigir a dívida que a integração deixou.

**Escopo**

- F1.1 Reconciliar o player: decidir entre (a) usar `AutomaticEpisodeAdvance.nextPlayableIndex` e `BingeAdvanceCoordinator` na integração, ou (b) removê-los e reapontar os quatro testes para o caminho real. Recomendação: **(a)** — preserva os testes e tira regra de negócio do Composable.
- F1.2 Homologar autoplay em aparelho físico usando a série de seed de F0.4: 4 avanços automáticos consecutivos, `binge_session` emitido exatamente uma vez, reset ao navegar manualmente.
- F1.3 Confirmar `binge_session` chegando em `playback_event` no backend e agregado em `/v1/admin/analytics/binge`.
- F1.4 Validar `/analytics/binge` autenticado em DEV nas janelas 7/30/90 dias, incluindo estado vazio (`RC2_E06_FINAL_VALIDATION.md`).

**Evidência para fechar F1**

- Unit tests Android verdes, sem teste referenciando classe não utilizada em `main`.
- APK `devDebug` verde no CI.
- Gravação ou checklist assinado do smoke em aparelho.
- Consulta SQL mostrando `binge_session` para a sessão de teste.
- Screenshot do dashboard com dado real.

**Risco declarado:** `RC2_E06_RISK.md` já aponta o certo — build verde não prova sessão autenticada do Studio consultando DEV. F1.4 é obrigatório, não opcional.

---

### F2 — Fechar D2 e D3 (jornada principal e comercial)

Executa os contratos que já existem, agora com ambiente capaz de sustentá-los.

**Escopo Android**

- F2.1 Percorrer o contrato de `D2_E2E_GATE.md` ponto a ponto (6 itens), em aparelho, com o seed de F0.4.
- F2.2 Jornada de monetização: episódio bloqueado → paywall → moeda/anúncio → desbloqueio → continuidade, com estado vindo do servidor.
- F2.3 Rewards: check-in, missões, resgate VIP contra `/v1/rewards/**`.

**Escopo Backend**

- F2.4 Testes para `MediaStorageService` e `VisitorMergeService` (D3 da seção 1.3) — pré-condição para confiar no fluxo de mídia e na fusão visitante→conta que F2.1 e F2.2 exercitam.
- F2.5 Verificar `GooglePurchaseApi` contra compra real de teste do Play.

**Escopo Studio**

- F2.6 E2E de conteúdo: criar drama → subir poster → subir vídeo → publicar → aparecer no app.
- F2.7 Operação comercial: catálogo de produtos, políticas de reward, visibilidade de compras.

**Evidência para fechar F2**

- Checklist dos 6 itens do D2 com resultado por item.
- Uma série criada inteiramente pelo Studio, reproduzindo no app, sem intervenção manual em banco ou S3.
- Uma compra de teste verificada ponta a ponta.
- Zero P0/P1 aberto nos fluxos acima.

---

### F3 — Hardening (D4)

**Escopo**

- F3.1 Estados de loading/erro/vazio em telas críticas das duas UIs, sem spinner bloqueante onde skeleton é possível.
- F3.2 Telas estreitas e restauração de sessão no Android.
- F3.3 Autorização: confirmar que todo `/v1/admin/**` rejeita token não-ADMIN; confirmar fail-closed nos fluxos de entitlement (`RC2_E06_ACCESS_FAIL_CLOSED.md`).
- F3.4 Normalização de dados nos dashboards; distinguir "zero" de "dados insuficientes" (ver M8).

**Evidência:** matriz de teste preenchida, nenhum P0/P1, autorização verificada por requisição negada e registrada em auditoria.

---

### F4 — RC1

**Escopo**

- F4.1 CI verde nas três frentes no mesmo commit de referência.
- F4.2 APK assinado DEV/RC publicado como artifact.
- F4.3 Deploy DEV do backend e do Studio a partir do mesmo ponto.
- F4.4 Freeze do contrato operacional.
- F4.5 Checklist de smoke completo e arquivado.

**Definition of Done RC1:** jornada do visitante, jornada autenticada, controles do player, monetização/rewards, persistência e navegação passam sem P0/P1; operações de conteúdo, comerciais e de IAM utilizáveis em DEV; nenhum asset alterado.

---

## 4. Re-baseline de calendário

O calendário original (D3 em 30/08, D4 em 31/08, RC1 em 01/09) não é executável: F0 é pré-requisito e não foi iniciado, e D3/D4 venceram sem atualização de progresso.

Proposta, para aprovação:

| Fase | Duração estimada | Depende de |
|---|---|---|
| F0 | 1–2 dias | — |
| F1 | 1 dia | F0.4, F0.1 |
| F2 | 2–3 dias | F0, F1 |
| F3 | 2 dias | F2 |
| F4 | 1 dia | F3 |

Total: **7–9 dias úteis** a partir da aprovação. F0.4 (seed com vídeo real) é o item de maior incerteza, porque depende de ativo de vídeo disponível e do upload multipart funcionando contra S3 em condição real.

Os percentuais 78%/80% devem ser recalculados só ao final de F0.5, quando os docs refletirem o código.

---

## 5. Melhorias propostas

Separadas por natureza. As de bloqueio já estão dentro das fases; as estruturais são candidatas a épico próprio.

### Bloqueadoras — já embutidas em F0/F1

**M1 — Wrappers de build versionados.** *(F0.1)* Maior alavancagem do plano inteiro. Elimina a causa dos 176 documentos de churn de CI e devolve ciclo de feedback local de segundos em vez de minutos de pipeline.

**M2 — `.gitignore` e keystore fora do Git.** *(F0.2, F0.3)*

**M3 — Seed de DEV reprodutível e versionado.** *(F0.4)* Não fazer manualmente: um script ou coleção de requisições versionada contra `/v1/admin/**`, para que qualquer pessoa recrie o ambiente. Seed manual é a razão pela qual DEV hoje está inconsistente com os gates.

**M4 — Reconciliação do player.** *(F1.1)*

### Estruturais — recomendadas antes ou junto de RC1

**M5 — Limiar de maratona server-driven.** Expor o limiar (e demais parâmetros de sinal) em endpoint de configuração consumido pelo app, com fallback local. Corrige D2 da seção 1.3 e alinha ao próprio contrato RC2. Sem isso, ajustar a definição de "maratona" custa um release de loja — inaceitável para uma métrica de produto que se pretende observar e calibrar.

**M6 — Teste onde há dinheiro, mídia e identidade.** `VisitorMergeService`, `MediaStorageService`, `WalletCreditService`, `RewardGrantService`, `VipAccessService`. Parcialmente em F2.4; o restante é épico próprio.

**M7 — Contract test automatizado Android↔Backend.** Hoje a compatibilidade dos 51 endpoints é garantida por uma frase no README. Proposta: backend publica contrato (OpenAPI) no CI e o Android valida seus DTOs contra ele. Transforma em falha de build o que hoje é falha de runtime em APK já distribuído.

### Higiene — pós-RC1 aceitável

**M8 — "Dados insuficientes" ≠ "zero" nos dashboards.** Hoje `/analytics/binge` sem dados é indistinguível de defeito. Vale para todos os 10 dashboards.

**M9 — Consolidar os 176 `RC2_E06_*.md`.** Substituir por um registro de decisão por épico. Preservar os que têm conteúdo real (`SCOPE_LOCK`, `DEFINITION_OF_DONE`, `PLAYER_PATCH_PLAN`, `ENTITLEMENT`, `ACCESS_FAIL_CLOSED`, `LIQUIBASE`, `METRIC_THRESHOLD`) e arquivar o resto. Depende de M1: sem build local, o padrão volta a se reproduzir.

**M10 — `RELEASE_PROGRESS.md` como saída de pipeline.** Percentual e status de gate derivados de resultado de CI e de contagem de P0/P1, não digitados. Elimina a classe de problema que este diagnóstico encontrou na seção 1.1.

---

## 6. Proposta de hierarquia — épico / história / tarefa / subtarefa

Rascunho para a próxima conversa. Rótulos neutros de propósito: E06 está fechando e E07 já está reservado no backlog, então a numeração definitiva é decisão do PO.

```
EPIC RC1-A — Destravamento de ambiente e verificação          [F0]
  ├─ HIST A1 — Build local reprodutível nas três frentes
  │    ├─ T A1.1 — Wrapper Gradle versionado (Android)
  │    ├─ T A1.2 — Wrapper Maven versionado (backend)
  │    └─ T A1.3 — Documentar bootstrap em máquina limpa
  ├─ HIST A2 — Higiene de repositório Android
  │    ├─ T A2.1 — .gitignore
  │    └─ T A2.2 — Keystore para Secrets  [decisão: histórico?]
  ├─ HIST A3 — Seed DEV capaz de sustentar os gates
  │    ├─ T A3.1 — Série com ≥5 episódios (≥4 livres em sequência)
  │    ├─ T A3.2 — Vídeo real em S3 via Studio
  │    └─ T A3.3 — Seed versionado e reexecutável
  └─ HIST A4 — Docs de progresso sincronizados com o código

EPIC RC1-B — Fechamento de E06 (Modo Maratona)                [F1]
  ├─ HIST B1 — Reconciliação do player
  │    ├─ T B1.1 — Integrar (ou remover) as duas classes órfãs
  │    └─ T B1.2 — Reapontar cobertura para o caminho real
  └─ HIST B2 — Homologação de maratona ponta a ponta
       ├─ T B2.1 — Smoke em aparelho
       ├─ T B2.2 — Verificar binge_session no backend
       └─ T B2.3 — Validar dashboard autenticado em DEV

EPIC RC1-C — Jornada principal e comercial                    [F2]
  ├─ HIST C1 — D2 Android (6 itens do contrato)
  ├─ HIST C2 — Monetização ponta a ponta
  ├─ HIST C3 — Rewards server-authoritative
  ├─ HIST C4 — E2E de conteúdo pelo Studio
  └─ HIST C5 — Testes de mídia e identidade no backend

EPIC RC1-D — Hardening                                        [F3]
  ├─ HIST D1 — Estados de loading/erro/vazio
  ├─ HIST D2 — Telas estreitas e restauração de sessão
  ├─ HIST D3 — Autorização e fail-closed verificados
  └─ HIST D4 — Normalização de dados dos dashboards

EPIC RC1-E — Release Candidate                                [F4]
  └─ HIST E1 — CI verde, artifact assinado, deploy, freeze, smoke

EPIC POST-1 — Configuração server-driven                      [M5]
EPIC POST-2 — Contrato Android↔Backend automatizado           [M7]
EPIC POST-3 — Confiabilidade de domínios sensíveis            [M6]
EPIC POST-4 — Governança de documentação e progresso     [M8/M9/M10]
```

**Caminho crítico:** A3 → B2 → C1. Tudo o mais paraleliza.

**Decisões pendentes do PO antes de virar backlog formal:**

1. Numeração: mapear RC1-A..E em E07+ ou manter trilha separada de release?
2. Keystore: reescrever histórico do Git ou apenas remover do HEAD?
3. M5 (limiar server-driven) entra antes do RC1 ou fica pós-RC?
4. Data-alvo de RC1 após o re-baseline da seção 4.
5. Asset freeze continua valendo durante F3 (hardening pode exigir ajuste visual)?

---

## 7. Fora do escopo deste plano

Refinamento cosmético, novas animações, novas funcionalidades de produto, offline, PiP e qualquer épico posterior seguem fora, conforme `RC2_E06_SCOPE_LOCK.md`. Asset freeze permanece em vigor até liberação explícita do product owner.
