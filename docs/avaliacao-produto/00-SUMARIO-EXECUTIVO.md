# 00 — Sumário Executivo

Data: 2026-08-31 · Audiência: CEO, sócios, product owner

---

## A frase que resume tudo

**O produto está bem construído e não está pronto para lançar. As duas coisas são verdadeiras ao mesmo tempo,
e a segunda não é culpa da primeira.**

Existem ~24.000 linhas de código de produção funcional, cobrindo catálogo, player, monetização,
recompensas, push, analytics e um painel administrativo com 29 telas. É um produto real, não um protótipo.

O que não existe é **tudo o que fica entre "o código funciona" e "o produto está no ar"**: ambiente de
produção, build assinado para loja, conformidade com a Google Play, conformidade com a LGPD, e capacidade
de operar o serviço depois que ele estiver rodando.

---

## Números

| Frente | Código de produção | Testes | Rotas / telas | Cobertura de CI |
|---|---|---|---|---|
| Android | 11.414 linhas Kotlin (95 arquivos) | 1.507 linhas (33 arquivos) | 13 telas | ⚠️ Só sob acionamento manual |
| Backend | 7.877 linhas Java (70 arquivos) | 2.251 linhas (38 arquivos) | 136 mapeamentos HTTP | ✅ A cada push |
| Studio | 4.933 linhas TS/TSX (44 arquivos) | **0** | 29 rotas | ⚠️ Só typecheck + build |

**Branch `main` das três frentes está 386 / 779 / 281 commits atrás de `develop`.**
Traduzindo: nada foi lançado ainda, nem uma vez. Não há histórico de release, não há tag, não há
rollback possível. O primeiro deploy de produção será também o primeiro ensaio de deploy de produção.

---

## Duas distâncias diferentes, medidas separadamente

### Distância 1 — Até o RC1 em DEV

Esta é a distância que o `PLANO_FINALIZACAO_RC1.md` já mapeou corretamente em 2026-08-30.

**Estimativa: 7 a 9 dias úteis.** Confirmo o diagnóstico e a estimativa.

O bloqueador crítico continua sendo o mesmo: **o ambiente DEV não consegue provar o que precisa provar.**
O catálogo DEV tem 3 episódios em 2 séries, com vídeos apontando para arquivos de amostra públicos do Google
(`db.changelog-master.yaml:133`). O evento de maratona exige 3 avanços consecutivos e a série mais longa tem
2 episódios — o evento é fisicamente inatingível. Sem consertar o seed, três gates não fecham.

### Distância 2 — Do RC1 até a loja

**Esta distância não está mapeada em lugar nenhum, e é maior que a primeira.**

**Estimativa: 4 a 7 semanas adicionais**, das quais 2 a 3 são tempo de espera externo (revisão da Google Play,
verificação de conta de desenvolvedor, propagação de DNS/certificados) que não acelera com mais gente.

---

## Os sete bloqueadores de lançamento que ninguém está rastreando

Cada um destes impede o lançamento por si só. Nenhum aparece no plano RC1, porque o plano RC1 não é sobre lançamento.

| # | Bloqueador | Consequência se ignorado | Evidência |
|---|---|---|---|
| **L1** | Não existe exclusão de conta no app | **Rejeição automática na Google Play.** Política obrigatória desde 2022 | Nenhuma ocorrência em `app/src/main` |
| **L2** | Não existe política de privacidade nem formulário Data Safety | **Rejeição na loja + exposição sob a LGPD** | Ausente nos três repositórios |
| **L3** | ID do AdMob é o ID **de teste do Google** | Receita de anúncio = **R$ 0**, ou banimento se trocado errado | `AndroidManifest.xml:33` — `ca-app-pub-3940256099942544~3347511713` |
| **L4** | Não existe build de release: sem `signingConfig`, sem R8, sem AAB | **Não há artefato publicável.** Só se gera APK debug | `app/build.gradle.kts` — nenhum bloco `release` |
| **L5** | Não existe ambiente de produção do backend | Não há para onde o app apontar | Só existe o profile `dev-cloud` |
| **L6** | Flavors `hml` e `prod` têm Firebase vazio | Push quebrado fora de DEV | `app/build.gradle.kts:44-48` vs. `:51-58` |
| **L7** | Zero observabilidade: sem métrica, traço, alerta ou rastreio de erro | Descobriremos incidentes pelas avaliações na Play Store | `application.yml` expõe só `health,info` |

---

## O que está genuinamente bom

Isto não é um relatório de demolição. Vários pontos difíceis foram resolvidos com competência:

- **Validação de compra Google Play é real e correta.** Verificação server-side contra a Android Publisher API v3,
  hash SHA-256 do token, idempotência por chave e crédito único de carteira
  (`GooglePurchaseApi.java:122-271`). Muitos produtos em produção têm isto pior.
- **Verificação SSV do AdMob é criptográfica de verdade** — ECDSA contra o conjunto de chaves do Google
  (`RewardedAdApi.java:265-269`), não um "confia no cliente".
- **Fronteira de autorização é simples e auditável** — uma única cadeia de filtros, `/v1/admin/**` sob
  `hasRole("ADMIN")`, stateless (`SecurityConfig.java:25-34`).
- **Estado de monetização é server-authoritative**, como o contrato exige. O cliente não decide direito de acesso.
- **Existe um laboratório de dispositivos** (`mobile-center/`) com Appium, matriz Android 12–15, noVNC e relatórios.
  Está desligado por variável de repositório, mas a engenharia está feita.
- **Zero `TODO`/`FIXME` no código de produção das três frentes.** Isso é raro e diz algo sobre a disciplina do time.

---

## O que preocupa de verdade

### 1. O time não consegue compilar o próprio produto

Nenhuma das três frentes constrói localmente: sem `gradlew`, sem `mvnw`, sem `node_modules`, sem `~/.m2`.
Toda validação depende do GitHub Actions.

O custo disso está visível e é mensurável: **176 arquivos `RC2_E06_*.md`** nos repositórios, com nomes como
`CI_NOW.md`, `CI_PLEASE.md`, `CI_GO_NOW.md`, `AWAIT_CI.md`. São commits vazios criados só para disparar pipeline.
Um deles, `RC2_E06_ABSOLUTE_END.md`, tem 20 bytes de conteúdo.

Isto não é desleixo de documentação. É o sintoma de um ciclo de feedback quebrado, e é a coisa de maior
alavancagem a corrigir no projeto inteiro. Um dia de trabalho devolve minutos por iteração para o resto da vida do produto.

### 2. O Android — a frente que vai para a loja — é a que tem menos CI

O workflow `android-apk.yml` roda apenas em `workflow_dispatch` manual ou em push para `release/**`.
**Não há CI em `develop`.** Os testes unitários do app só rodam quando alguém pede.

Ou seja: a frente com 11.414 linhas, que produz o artefato que chega ao usuário final e que não pode
ser corrigida a quente depois de publicada, é a menos protegida das três.

### 3. O Studio tem zero testes

44 arquivos, 4.933 linhas, 29 rotas, nenhum teste. O CI faz `typecheck` e `build`. Há inclusive um commit
histórico removendo um teste Vitest por incompatibilidade (`c2fb341`).

O Studio é o painel por onde a operação publica conteúdo, configura preço e concede recompensa.
É a superfície de maior poder do sistema e a de menor proteção.

### 4. A compatibilidade Android↔Backend é garantida por uma frase em README

O app consome 59 endpoints. A compatibilidade está declarada em uma frase no README do backend. Nada automatizado
a verifica. Uma renomeação de campo passa verde no CI das duas frentes e quebra em runtime no APK já instalado
no telefone do usuário — que não pode ser corrigido sem nova submissão à loja.

---

## Recomendação

### Não lançar em setembro. Lançar em outubro, com produção de verdade.

Forçar uma data em setembro só é possível cortando conformidade (L1, L2) ou ambiente de produção (L5, L7).
Cortar L1/L2 resulta em rejeição na loja — não é um atalho, é um desvio mais longo. Cortar L5/L7 resulta em
lançar às cegas: sem alerta, sem métrica, sem rollback, sobre um `main` que nunca recebeu um deploy.

### Sequência recomendada

| Etapa | Duração | Entrega |
|---|---|---|
| **Fase 0 — Destravar** | 2 dias | Build local nas 3 frentes; CI em `develop` no Android; seed DEV utilizável |
| **Fase 1 — RC1 em DEV** | 6 dias | O escopo do `PLANO_FINALIZACAO_RC1.md` já aprovado |
| **Fase 2 — Conformidade** | 5 dias | Exclusão de conta, política de privacidade, Data Safety, LGPD |
| **Fase 3 — Produção** | 8 dias | Ambiente PROD, build assinado, observabilidade, runbook |
| **Fase 4 — Loja** | 10–20 dias | Submissão, revisão da Google, teste fechado, correções |

**Janela realista de lançamento: segunda quinzena de outubro de 2026.**

Detalhamento em [06 — Caminho para o Lançamento](06-CAMINHO-PARA-LANCAMENTO.md).

---

## Cinco decisões que só o PO/CEO pode tomar, e que travam o plano

1. **Data-alvo de lançamento.** Outubro com produção completa, ou setembro com um beta fechado sem monetização?
2. **Escopo do lançamento.** Lançar com monetização ativa exige SKUs configurados na Play Console e conta de
   pagamento verificada — prazo externo que não controlamos. Lançar sem monetização remove essa dependência.
3. **Ambiente de homologação.** Vale investir em HML, ou vamos de DEV direto para PROD? Hoje o flavor `hml`
   existe no código e aponta para uma infraestrutura que não existe.
4. **Quem opera o produto depois do lançamento?** Não há plantão, alerta ou runbook definido. Isto é uma
   decisão de estrutura, não de engenharia.
5. **Orçamento de infraestrutura mensal.** Nenhuma estimativa de custo operacional foi produzida até hoje.
   Ver [04 — Visão Financeira](04-VISAO-FINANCEIRA.md).

---

## Uma observação final, honesta

O projeto tem um problema de **percepção de progresso**, não de progresso.

Os documentos `RELEASE_PROGRESS.md` das três frentes declaram 78%, 80% e 84% de prontidão para produção,
com data de 2026-08-28 — três dias atrás, e desatualizados desde então. Esses percentuais medem a distância
até o RC1 em DEV, mas estão rotulados como *"production readiness"*.

Medidos contra o lançamento real, com conformidade e produção incluídas, os números estão mais próximos de
**55% a 60%**. A diferença não é código que falta escrever — é ambiente, conformidade e operação que ninguém
começou porque ninguém colocou na conta.

Este dossiê existe principalmente para colocar na conta.
