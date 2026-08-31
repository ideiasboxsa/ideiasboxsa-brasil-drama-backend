# 03 — Visão de CTO

Data: 2026-08-31 · Perspectiva: CTO — risco, conformidade, operação, organização

---

## 1. A pergunta que um CTO precisa responder

Não é *"o código está bom?"* — está. É:

> **Se lançarmos este produto na semana que vem, o que acontece?**

Resposta honesta, em ordem cronológica:

1. **Dia 0** — A submissão é rejeitada pela Google Play. Falta exclusão de conta e política de privacidade.
   Não chegamos ao ar.
2. **Se contornarmos isso** — Não há para onde publicar. Não existe ambiente de produção do backend,
   nem build assinado do app.
3. **Se contornarmos isso também** — Estamos no ar e cegos. Sem métrica, sem alerta, sem rastreio de erro.
   O primeiro sinal de incidente será uma avaliação de uma estrela.
4. **E os anúncios não geram receita**, porque o ID do AdMob é o de teste do Google.

Nenhum desses quatro pontos é sobre qualidade de código. Todos são sobre o que fica entre o código e o produto.

---

## 2. Matriz de risco

Probabilidade × Impacto, avaliados para o cenário "lançar em 30 dias no ritmo atual".

| ID | Risco | Prob. | Impacto | Nível |
|---|---|---|---|---|
| R1 | Rejeição na Google Play por exclusão de conta ausente | **Certa** | Alto | 🔴 |
| R2 | Rejeição/exposição por ausência de política de privacidade e Data Safety | **Certa** | Alto | 🔴 |
| R3 | Receita de anúncio = zero por ID de teste do AdMob | **Certa** | Médio | 🔴 |
| R4 | Não há artefato publicável (sem release/signing/AAB) | **Certa** | Alto | 🔴 |
| R5 | Incidente em produção não detectado por falta de observabilidade | Alta | Alto | 🔴 |
| R6 | Quebra de contrato Android↔Backend em APK já distribuído | Média | **Crítico** | 🔴 |
| R7 | Abuso de `/v1/auth/register` para emitir moedas em massa | Média | Alto | 🟠 |
| R8 | Exposição sob a LGPD por ausência de canal de titular de dados | Alta | Médio-Alto | 🟠 |
| R9 | Perda de receita por compras não confirmadas (reembolso em 3 dias) | Média | Médio | 🟠 |
| R10 | Primeiro deploy de produção sem ensaio, sobre `main` nunca implantada | Alta | Alto | 🟠 |
| R11 | Migração de banco sem rollback definido | Baixa | Alto | 🟠 |
| R12 | Concentração de conhecimento; nenhum runbook escrito | Alta | Médio | 🟠 |
| R13 | Build do Studio não reproduzível (sem lockfile) | Média | Médio | 🟡 |
| R14 | Token administrativo em `sessionStorage` sem CSP | Baixa | Alto | 🟡 |

**Seis riscos vermelhos, dos quais quatro são certezas, não probabilidades.** Um risco com probabilidade 1
não é risco — é um item de trabalho ainda não escrito no plano.

---

## 3. Conformidade — a categoria inteiramente ausente

Esta é a lacuna mais séria do projeto, e a razão é estrutural: **nenhum dos três repositórios contém uma
única linha sobre conformidade.** Não é que esteja mal feita; é que não foi considerada.

### 3.1 Política da Google Play

| Exigência | Situação | Consequência |
|---|---|---|
| Exclusão de conta no app **e** por URL web | ❌ Ausente | **Rejeição.** Obrigatório desde 2022 |
| Formulário Data Safety preenchido | ❌ Ausente | **Rejeição** |
| Política de privacidade acessível | ❌ Ausente | **Rejeição** |
| Declaração de uso do Advertising ID | ❌ Ausente | **Rejeição** (o app usa AdMob) |
| Classificação indicativa | ❌ Não iniciada | Bloqueia publicação |
| Conta de desenvolvedor verificada | ⚠️ Não verificável | Verificação de identidade leva dias/semanas |
| SKUs de compra criados na Play Console | ⚠️ Não verificável | 8 produtos definidos no banco (`016-monetization-catalog.yaml`) precisam existir lá |
| `assetlinks.json` publicado em `brasildrama.com.br` | ❌ Ausente | Deep links com `autoVerify` falham silenciosamente |

O app declara três `intent-filter` com `android:autoVerify="true"` apontando para `brasildrama.com.br`
(`AndroidManifest.xml:44-56`). Sem o arquivo de verificação hospedado no domínio, o Android não associa os
links e o compartilhamento — que é o principal canal de aquisição de um produto de microdramas — não abre no app.

### 3.2 LGPD

O produto coleta e-mail, nome, histórico completo de reprodução, token de dispositivo, identificador de
visitante anônimo e telemetria comportamental por superfície.

| Direito do titular (Art. 18) | Implementado |
|---|---|
| Confirmação de existência de tratamento | ❌ |
| Acesso aos dados | ❌ |
| Correção | ⚠️ Parcial (`PUT /v1/me` altera perfil) |
| Anonimização, bloqueio ou eliminação | ❌ |
| Portabilidade | ❌ |
| Eliminação de dados tratados com consentimento | ❌ |
| Revogação de consentimento | ❌ |

Não há aviso de privacidade, não há registro de consentimento, não há encarregado (DPO) designado, não há
política de retenção. `playback_event` acumula comportamento individual indefinidamente, sem prazo nem
processo de expurgo.

Há um detalhe agravante e específico: `VisitorMergeService` vincula retroativamente toda a atividade anônima
de um visitante à conta no momento do cadastro (`VisitorMergeService.java:62-70`) — incluindo o histórico de
reprodução anterior ao consentimento. É um tratamento de dados que precisa de base legal explícita e de
divulgação ao titular. Hoje não tem nenhuma das duas.

**Isto não é opcional e não é caro.** Exclusão de conta ponta a ponta, política de privacidade e formulário
Data Safety somam cerca de 5 dias de trabalho. O que custa caro é descobrir isso na terceira rejeição da loja.

---

## 4. Prontidão operacional

### 4.1 Observabilidade: inexistente

```yaml
# application.yml
management.endpoints.web.exposure.include: health,info
```

É isso. O sistema inteiro expõe dois endpoints de gestão.

| Capacidade | Situação |
|---|---|
| Métricas (Prometheus/CloudWatch) | ❌ Não exportadas |
| Rastreamento distribuído | ❌ |
| Rastreio de erro (Sentry ou equivalente) | ❌ |
| Log estruturado / agregação | ❌ Padrão do Spring, sem correlação |
| Alertas | ❌ |
| SLO ou SLI definido | ❌ |
| Dashboard operacional | ❌ |
| Crash reporting no Android (Crashlytics) | ❌ Firebase presente, só messaging |

O Studio tem dez dashboards de **analytics de produto** — funil, maratona, aquisição por superfície,
continuidade, recomendação. É uma boa instrumentação de negócio.

Não há um único painel de **saúde técnica**. Sabemos qual episódio tem mais abandono; não sabemos a latência
p99 da API, a taxa de erro 5xx, ou se o serviço caiu há dez minutos.

Essa assimetria é reveladora: o time instrumentou o que o produto pede e não instrumentou o que a operação
pede — porque ninguém ainda ocupou o papel de operar.

### 4.2 Implantação e reversão

| | Backend | Studio | Android |
|---|---|---|---|
| Deploy DEV automatizado | ⚠️ só publica imagem | ✅ completo, com verificação | ⚠️ APK como artifact |
| Deploy HML | ❌ | ❌ | ❌ |
| Deploy PROD | ❌ | ❌ | ❌ |
| Procedimento de rollback | ❌ | ⚠️ implícito por re-deploy | N/A (loja) |
| Runbook de incidente | ❌ | ❌ | ❌ |
| Ensaio de deploy | ❌ | ❌ | ❌ |

**`main` está 386 / 779 / 281 commits atrás de `develop` nas três frentes.** Nunca houve um release.
O primeiro deploy de produção será simultaneamente o primeiro teste do processo de deploy de produção,
executado sob a pressão de um lançamento. É o cenário em que incidentes acontecem.

### 4.3 Segredos e credenciais

| Item | Situação |
|---|---|
| Keystore de debug versionado | ⚠️ `ci/brasil-drama-dev-debug.keystore.b64` — risco baixo, mas contraria o princípio declarado |
| Chave de API do Firebase no `build.gradle.kts` | 🟡 Aceitável — chaves Android do Firebase são identificadores públicos por desenho, protegidas por regras de segurança, não por sigilo |
| `.gitignore` no Android | ❌ **Ausente** — nada impede o próximo `.jks` ou `local.properties` de entrar |
| Segredos do backend | ✅ Todos por variável de ambiente |
| Rotação de segredos | ❌ Sem processo |
| Cofre de segredos | ⚠️ GitHub Secrets; sem gestor dedicado |

O item mais concreto aqui não é o keystore — é a **ausência de `.gitignore`**. O keystore de debug já
vazado é inofensivo; o que preocupa é que não há nada estruturalmente impedindo que um keystore de release
seja commitado no dia em que ele for criado.

---

## 5. Riscos de organização e time

Esta seção infere a partir de padrões no repositório, não de conhecimento direto do time. Trate como hipótese
a validar.

**Sinal 1 — Alta velocidade, baixa proteção.** 779 commits à frente de `main` no Android, com integração
contínua desligada nessa frente. O time produz muito e verifica pouco. Isso funciona enquanto o produto está
em construção e falha no dia em que houver usuário real.

**Sinal 2 — Ferramenta inadequada absorvida como custo, não reportada como problema.** Os 176 arquivos de
churn de CI mostram um time que se adaptou a uma restrição em vez de escalá-la. Isso é resiliência, e também
é um sintoma de que problemas de plataforma não têm um canal para virar prioridade.

**Sinal 3 — Documentação de progresso desconectada do código.** O `PLANO_FINALIZACAO_RC1.md` documenta que a
integração do E06 estava concluída em código enquanto o documento de progresso ainda a listava como pendente.
Decisões estavam sendo tomadas sobre informação errada. O plano já propõe a correção certa (M10: progresso
como saída de pipeline, não digitado).

**Sinal 4 — Nenhum papel de operação.** A ausência total de observabilidade, runbook e plantão não é
esquecimento técnico. É a assinatura de uma organização que ainda não tem alguém cujo trabalho é manter o
serviço no ar. Isso precisa ser resolvido antes do lançamento, e é uma decisão de estrutura, não de engenharia.

---

## 6. Decisões técnicas que exigem posição do CTO

### D1 — Ambiente de homologação: existe ou não?

Os flavors `hml` estão no código apontando para `hml-api.brasildrama.com.br`, sem profile no backend e sem
infraestrutura. Duas saídas coerentes:

- **(a)** Construir HML de verdade. Custo: ~5 dias + infraestrutura mensal duplicada.
- **(b)** Remover o flavor `hml` e assumir DEV → PROD, com bandeiras de funcionalidade para mitigar.

**Recomendo (b) para o lançamento.** Um HML mal mantido é pior que nenhum: dá a sensação de rede sem
sustentar peso. Reavaliar depois que o produto tiver tráfego.

### D2 — Monetização no lançamento: ativa ou desligada?

Lançar com monetização adiciona dependências externas fora do nosso controle: conta de pagamento verificada,
8 SKUs criados e aprovados, conta AdMob ativa, testes de compra reais. Cada uma tem prazo próprio.

- **(a)** Lançar completo — maior receita desde o dia 1, maior risco de prazo e mais superfície na revisão.
- **(b)** Lançar sem monetização, ativar em seguida — caminho mais curto para a loja, valida retenção antes
  de otimizar receita, revisão mais simples.

**Recomendo (b)** *se* a data importar mais que a receita imediata. O código já suporta: as regras de acesso
são server-authoritative e o catálogo comercial é uma tabela — desligar é configuração, não refatoração.

### D3 — Quando pagar a dívida de contrato Android↔Backend?

R6 é o único risco de impacto **crítico** na matriz, porque é o único que não tem correção rápida depois de
acontecer: um APK quebrado na mão do usuário exige nova submissão e espera de revisão.

**Recomendo tratar antes do lançamento**, não depois. Dois dias de trabalho agora contra um incidente que
leva de 3 a 7 dias para resolver e queima confiança nas primeiras avaliações da loja.

### D4 — Quem está de plantão no dia do lançamento?

Sem resposta a esta pergunta, não há data de lançamento — só uma data de publicação.

---

## 7. Roadmap técnico pós-lançamento

Fora do escopo do lançamento, mas deve estar visível ao decidir o que cortar agora:

| Prioridade | Item | Motivo |
|---|---|---|
| Alta | Configuração server-driven (M5) | Calibrar produto sem ciclo de loja |
| Alta | Contract test automatizado (M7) | Elimina a classe inteira do risco R6 |
| Alta | Cobertura em mídia/identidade/monetização (M6) | Onde há dinheiro, mídia e identidade |
| Média | Particionamento e retenção de `playback_event` | Tabela de maior crescimento, sem política |
| Média | Testes do Studio | Superfície de maior poder, proteção zero |
| Média | Mobile Center ligado com cenários reais | Ferramenta pronta, parada |
| Baixa | Quebrar arquivos `*Api.java` de 300+ linhas | Custo de onboarding a prazo |
| Baixa | Consolidar os 176 `RC2_E06_*.md` (M9) | Higiene; resolve-se sozinho com build local |

---

## 8. Veredito do CTO

**A engenharia entregou. A plataforma não existe ainda.**

Em 24 mil linhas de código de produção encontrei nove defeitos concretos, dos quais oito se corrigem em
horas. Isso é um índice de qualidade acima da média do mercado para um produto nesta fase, e o time merece
esse crédito explicitamente.

O que falta não é código. É:

- **conformidade** — nunca foi iniciada, é obrigatória, e é a única categoria que pode causar rejeição
  repetida na loja;
- **produção** — não existe, e é o item de maior prazo;
- **operação** — não há observabilidade, runbook, nem alguém designado.

Minha posição: **não lançar antes de fechar conformidade e produção**, e usar essas semanas para pagar os
riscos R6, R7 e R9 — que são baratos agora e caros depois.

A pressão por uma data em setembro é compreensível e deve ser resistida por um motivo pragmático, não por
perfeccionismo: **os caminhos que economizam tempo agora custam mais tempo depois.** Pular conformidade
resulta em rejeição e re-submissão. Pular observabilidade resulta em incidente descoberto pela avaliação do
usuário. Nenhum dos dois é um atalho.
