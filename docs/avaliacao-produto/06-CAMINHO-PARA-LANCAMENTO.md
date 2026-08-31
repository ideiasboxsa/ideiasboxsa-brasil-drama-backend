# 06 — Caminho para o Lançamento

Data: 2026-08-31 · Plano executável do estado atual até a loja

---

## Premissa

Este plano **não substitui** o `PLANO_FINALIZACAO_RC1.md`. Ele o contém.

O plano RC1 cobre as fases F0 e F1 abaixo e continua sendo a referência detalhada para elas. Este documento
acrescenta as fases F2, F3 e F4 — conformidade, produção e loja — que não estavam mapeadas em nenhum lugar.

```
PLANO_FINALIZACAO_RC1.md  ─────►  F0 · F1        (RC1 em DEV)
Este documento            ─────►  F0 · F1 · F2 · F3 · F4   (produto na loja)
```

---

## Visão geral

| Fase | Objetivo | Dias-pessoa | Calendário | Depende de |
|---|---|---|---|---|
| **F0** | Destravar verificação | 4 | 2 dias | — |
| **F1** | RC1 homologado em DEV | 12 | 6 dias | F0 |
| **F2** | Conformidade legal e de loja | 8 | 5 dias | — (paralela a F1) |
| **F3** | Produção operável | 14 | 8 dias | F0 |
| **F4** | Submissão e revisão | 4 | 10–20 dias | F1, F2, F3 |
| | **Total** | **42** | **5–7 semanas** | |

**F2 e F3 não dependem de F1** e devem começar em paralelo. Este é o principal ganho de prazo disponível:
sequenciar as três custa 3 semanas a mais e não melhora nada.

**Janela de lançamento: segunda quinzena de outubro de 2026.**

---

## F0 — Destravar (2 dias)

Nada aqui é funcionalidade. É a infraestrutura de verificação sem a qual as outras fases não podem ser validadas.

| # | Tarefa | Esforço | Frente |
|---|---|---|---|
| F0.1 | CI do Android em `develop` rodando `testDevDebugUnitTest` | 20 min | Android |
| F0.2 | Versionar `gradlew` + `gradle/wrapper/` | 1 h | Android |
| F0.3 | Versionar `mvnw` + `.mvn/wrapper/` | 1 h | Backend |
| F0.4 | Criar `.gitignore` no Android | 30 min | Android |
| F0.5 | Mover keystore de debug para Secrets; remover do tracking | 2 h | Android |
| F0.6 | Gerar `package-lock.json`; trocar `npm install` por `npm ci` | 30 min | Studio |
| F0.7 | Remover default do `JWT_SECRET`; falhar rápido sem a variável | 15 min | Backend |
| F0.8 | **Seed DEV: série com ≥5 episódios (≥4 livres em sequência) e vídeo real em S3 via Studio** | 1 dia | Backend/Studio |
| F0.9 | Script de seed versionado e reexecutável | 4 h | Backend |
| F0.10 | Sincronizar os `RELEASE_PROGRESS.md` com o código real | 2 h | Todas |

### Critério de saída

- [ ] `./gradlew testDevDebugUnitTest` e `./mvnw -q test` executam em máquina limpa
- [ ] CI do Android verde em push para `develop`
- [ ] `git ls-files` do Android sem artefato de build e sem keystore
- [ ] `GET /v1/catalog/dramas/{id}` do seed retorna ≥5 episódios com `videoUrl` em `brasil-drama-dev-media`
- [ ] Nenhum `RELEASE_PROGRESS.md` com data anterior ao fechamento de F0

> **F0.8 é o caminho crítico do projeto inteiro.** Sem ele, o modo maratona não pode ser homologado, o gate
> D2 de conteúdo não fecha, e todos os dashboards de analytics exibem zero de forma indistinguível de defeito.

---

## F1 — RC1 em DEV (6 dias, após F0)

Escopo detalhado em `PLANO_FINALIZACAO_RC1.md` §F1 e §F2. Resumo com os defeitos deste dossiê incorporados:

| # | Tarefa | Esforço | Ref. |
|---|---|---|---|
| F1.1 | Reconciliar o player: integrar `AutomaticEpisodeAdvance` e `BingeAdvanceCoordinator` | 4 h | D-06 |
| F1.2 | Corrigir transação do `restore()` de compras | 1 h | D-01 |
| F1.3 | Confirmação de compra no servidor, tornando o cliente redundante | 4 h | D-04 / F-01 |
| F1.4 | Rate limit em `/v1/auth/**` e no ingest de telemetria | 1 dia | D-03 |
| F1.5 | Verificação de e-mail antes do bônus de boas-vindas | 4 h | F-03 |
| F1.6 | Remover `usesCleartextTraffic`; ajustar `allowBackup` | 2 h | D-02 |
| F1.7 | Testes de `MediaStorageService` e `VisitorMergeService` | 1 dia | — |
| F1.8 | Homologar maratona em aparelho: 4 avanços, evento único, reset manual | 4 h | F1.2 do plano |
| F1.9 | Validar `/analytics/binge` autenticado em DEV, janelas 7/30/90 e estado vazio | 4 h | F1.4 do plano |
| F1.10 | Percorrer os 6 itens do `D2_E2E_GATE.md` em aparelho | 1 dia | F2.1 do plano |
| F1.11 | E2E de conteúdo pelo Studio: criar → poster → vídeo → publicar → reproduzir | 4 h | F2.6 do plano |
| F1.12 | Contract test OpenAPI Android↔Backend | 2 dias | Risco R6 |

### Critério de saída

- [ ] CI verde nas três frentes no mesmo commit de referência
- [ ] Nenhum teste referenciando classe não utilizada em `main`
- [ ] Consulta SQL mostrando `binge_session` para a sessão de teste
- [ ] Uma série criada inteiramente pelo Studio reproduzindo no app, sem toque manual em banco ou S3
- [ ] Checklist dos 6 itens do D2 preenchido, resultado por item
- [ ] Zero P0/P1 aberto

---

## F2 — Conformidade (5 dias, paralela a F1)

**Categoria com zero trabalho iniciado. Comece imediatamente** — o item jurídico tem prazo externo e é
caminho crítico para F4.

| # | Tarefa | Esforço | Responsável |
|---|---|---|---|
| F2.1 | Exclusão de conta no app: tela, confirmação, chamada de API | 1 dia | Android |
| F2.2 | Endpoint de exclusão: anonimizar/apagar dados pessoais, preservar recibo fiscal | 1 dia | Backend |
| F2.3 | Página web de exclusão de conta (exigência da Play) | 4 h | Studio ou site |
| F2.4 | Endpoint de exportação de dados (LGPD Art. 18, portabilidade) | 4 h | Backend |
| F2.5 | Redigir política de privacidade e termos de uso | — | **Jurídico** |
| F2.6 | Publicar ambos em URL pública estável | 2 h | Infra |
| F2.7 | Tela de consentimento no primeiro uso; registrar o consentimento | 1 dia | Android + Backend |
| F2.8 | Base legal para a fusão visitante→conta; divulgar ao titular | 4 h | Jurídico + Backend |
| F2.9 | Política de retenção de `playback_event`; rotina de expurgo | 4 h | Backend |
| F2.10 | Designar encarregado (DPO) e publicar canal de contato | — | **Empresa** |
| F2.11 | Preencher o formulário Data Safety da Play Console | 4 h | Produto |
| F2.12 | Declaração de uso do Advertising ID | 1 h | Produto |
| F2.13 | Classificação indicativa | 2 h | Produto |
| F2.14 | Publicar `assetlinks.json` em `brasildrama.com.br` | 2 h | Infra |

### Critério de saída

- [ ] Usuário consegue excluir a conta pelo app e por URL web
- [ ] Após a exclusão, nenhum dado pessoal permanece consultável
- [ ] Política de privacidade e termos acessíveis por URL pública
- [ ] Data Safety preenchido e coerente com o que o app coleta de fato
- [ ] DPO designado e canal publicado
- [ ] Deep links abrindo o app em aparelho real

> **F2.5 e F2.10 dependem de terceiros e não são aceleráveis por engenharia.** Iniciar no dia 1.

---

## F3 — Produção operável (8 dias, paralela a F1)

| # | Tarefa | Esforço | Frente |
|---|---|---|---|
| F3.1 | Criar `application-prod.yml` | 2 h | Backend |
| F3.2 | Provisionar infraestrutura PROD: rede, RDS, container, TLS, DNS | 3 dias | Infra |
| F3.3 | Gerenciador de segredos + rotação | 4 h | Infra |
| F3.4 | **CloudFront na frente da mídia S3** | 1 dia | Infra |
| F3.5 | Workflow de deploy do backend com aprovação manual | 1 dia | Backend |
| F3.6 | Deploy PROD do Studio | 4 h | Studio |
| F3.7 | Cabeçalhos de segurança no CloudFront do Studio (CSP, HSTS) | 4 h | Infra |
| F3.8 | Backup automatizado do banco + **teste de restauração** | 1 dia | Infra |
| F3.9 | Exportar métricas; criar painel de saúde técnica | 1 dia | Backend |
| F3.10 | Rastreio de erro nas três frentes (backend, Studio, Crashlytics) | 1 dia | Todas |
| F3.11 | Alertas: taxa de 5xx, latência p99, saúde do serviço, custo AWS | 4 h | Infra |
| F3.12 | Build de release do Android: `signingConfig`, R8, AAB | 1 dia | Android |
| F3.13 | Upload key gerada e guardada em Secrets; Play App Signing | 4 h | Android |
| F3.14 | Firebase para os flavors `hml`/`prod` | 2 h | Android |
| F3.15 | ID de produção do AdMob por flavor | 1 h | Android |
| F3.16 | Runbook de incidente e procedimento de rollback | 1 dia | Todas |
| F3.17 | **Ensaio de deploy completo em PROD, com rollback exercitado** | 1 dia | Todas |

### Critério de saída

- [ ] Backend PROD respondendo em `api.brasildrama.com.br` com TLS
- [ ] Studio PROD acessível, com CSP e HSTS
- [ ] Mídia servida via CloudFront
- [ ] AAB assinado gerado pelo CI
- [ ] Painel de saúde ativo, alertas disparando em teste
- [ ] Backup restaurado com sucesso em ambiente separado
- [ ] Rollback exercitado e cronometrado
- [ ] Runbook escrito e revisado por quem estará de plantão

> **F3.17 não é burocracia.** O `main` das três frentes nunca recebeu um deploy. Sem ensaio, o primeiro
> deploy de produção acontece sob pressão de lançamento — que é exatamente quando incidentes acontecem.

---

## F4 — Submissão e revisão (10 a 20 dias)

Prazo dominado por espera externa. Não acelera com mais pessoas.

| # | Tarefa | Duração |
|---|---|---|
| F4.1 | Verificar conta de desenvolvedor Google Play | dias a semanas (externo) |
| F4.2 | Criar os 8 SKUs na Play Console | 4 h |
| F4.3 | Configurar conta de pagamento e dados fiscais | externo |
| F4.4 | Ficha da loja: descrição, capturas, ícone, vídeo | 1 dia |
| F4.5 | Faixa de teste interno; validar compra real de teste | 2 dias |
| F4.6 | Teste fechado com usuários reais | 3 a 7 dias |
| F4.7 | Corrigir o que o teste fechado revelar | variável |
| F4.8 | Submeter para produção | 1 h |
| F4.9 | Revisão da Google | 1 a 7 dias (externo) |
| F4.10 | Corrigir e re-submeter, se rejeitado | +1 a 7 dias por ciclo |

### Critério de saída

- [ ] App aprovado e publicado
- [ ] Compra real de teste verificada ponta a ponta
- [ ] Anúncio premiado servindo anúncio real e creditando corretamente
- [ ] Push entregue em aparelho de produção
- [ ] Painéis de saúde recebendo tráfego real

> **Planejar duas submissões.** A primeira submissão de um app de mídia com compras e anúncios raramente
> passa de primeira. Orçar um ciclo de rejeição no calendário não é pessimismo — é a média.

---

## Cronograma proposto

```
Semana 1   F0 ████                    Destravar
           F2 ████████████████        Conformidade (jurídico inicia no dia 1)
           F3 ████████                Infra PROD inicia

Semana 2   F1 ████████████████        RC1 em DEV
           F2 ████████                Conformidade conclui
           F3 ████████████████        Produção

Semana 3   F1 ████████                RC1 fecha
           F3 ████████████████        Produção conclui + ensaio de deploy

Semana 4   F4 ████████████████        Teste interno e fechado

Semana 5-7 F4 ████████████████████    Submissão, revisão, correções
```

**Caminho crítico:** `F0.8 (seed) → F1.8 (homologação em aparelho) → F4.5 (compra de teste) → F4.9 (revisão)`

**Segundo caminho crítico, independente do primeiro:** `F2.5 (jurídico) → F2.11 (Data Safety) → F4.8 (submissão)`

Os dois convergem em F4. Atrasar qualquer um atrasa o lançamento na mesma proporção — e o segundo depende de
terceiros, o que o torna o mais perigoso dos dois para deixar para depois.

---

## Cenários alternativos

### Cenário A — Lançamento antecipado sem monetização

Remove F4.2, F4.3 e F4.5 do caminho crítico e simplifica a revisão da Google.

**Ganho: 1 a 2 semanas.** Lançamento no início de outubro.

Exige: desativar paywall e anúncio premiado por configuração (o catálogo comercial é uma tabela; as regras
de acesso são server-authoritative — é configuração, não refatoração). F2 e F3 permanecem obrigatórias.

**Vale a pena se** validar retenção importa mais que receita imediata — o que, num produto de microdramas
sem base instalada, costuma ser verdade.

### Cenário B — Beta fechado em setembro

Publicar em faixa de teste fechada, sem produção completa.

**Ganho: feedback real ainda em setembro.**

Exige F0, F1 e a parte de conformidade que a faixa fechada cobra (menos rigorosa, mas exclusão de conta e
política de privacidade continuam necessárias). Não exige F3 completa.

**Risco:** criar a impressão de que o produto lançou. Beta fechado é instrumento de aprendizado, não marco
comercial — e precisa ser comunicado assim internamente.

### Cenário C — Forçar setembro em produção

**Não recomendado, e o motivo é aritmético, não de opinião.**

Exigiria cortar F2 (rejeição certa na loja — não é atalho, é desvio mais longo) ou F3 (lançar sem
observabilidade, sem backup testado e sem rollback, sobre um `main` que nunca recebeu deploy).

O caminho aparentemente mais curto termina mais tarde: cada ciclo de rejeição custa de 1 a 7 dias, e um
incidente não detectado em produção custa mais que isso mais a confiança das primeiras avaliações da loja.

---

## Governança do plano

**Regra 1 — Progresso é saída de pipeline, não texto digitado.**
Os `RELEASE_PROGRESS.md` estavam três dias desatualizados e declaravam pendente um épico já integrado.
Percentual e status de gate devem derivar de resultado de CI e de contagem de P0/P1. (M10 do plano existente.)

**Regra 2 — Uma frente não declara pronto sozinha quando o contrato envolve outra.**
Já vigente no `RC2_PRODUCT_EXPERIENCE_CONTRACT.md`, e a regra que o contract test de F1.12 automatiza.

**Regra 3 — Cada fase fecha com artefato verificável.**
Checklist assinado, consulta SQL, captura de tela, log de CI. No padrão que o `D2_E2E_GATE.md` já usa.

**Regra 4 — Nenhum escopo novo até o lançamento.**
O asset freeze permanece. Polimento cosmético vai para pós-lançamento. Esta regra vai ser testada em F3,
quando o hardening revelar ajustes visuais desejáveis — e é aí que ela precisa valer.

---

## Primeiras cinco ações, para começar amanhã

1. **Adicionar CI do Android em `develop`.** 20 minutos. Maior retorno por minuto do projeto inteiro.
2. **Acionar o jurídico para política de privacidade e termos.** Prazo externo, caminho crítico, custo zero
   para iniciar hoje.
3. **Trocar o ID de teste do AdMob** — ou registrar formalmente que a receita de anúncio é zero até que se troque.
4. **Criar o seed DEV com 5 episódios e vídeo real.** Destrava três gates de uma vez.
5. **Decidir: monetização no lançamento, sim ou não?** A resposta muda o cronograma em 1 a 2 semanas e não
   pode ser adiada — F4.2 e F4.3 precisam iniciar cedo se a resposta for sim.
