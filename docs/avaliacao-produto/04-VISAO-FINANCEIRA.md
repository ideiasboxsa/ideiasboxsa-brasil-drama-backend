# 04 — Visão Financeira

Data: 2026-08-31 · Perspectiva: CFO — custo, receita, risco financeiro, capital até o lançamento

---

## Aviso metodológico, antes de qualquer número

**Nenhum dado financeiro real da operação foi disponibilizado para esta análise.** Não há acesso a faturas
AWS, contratos, folha, orçamento aprovado ou projeções comerciais.

O que se pode fazer a partir do repositório é diferente e ainda assim útil:

1. **Ler a mecânica financeira que está codificada** — SKUs, economia de moedas, política de bônus, quem
   fica com qual percentual. Isso é fato, não estimativa.
2. **Identificar receita em risco por defeito de código** — quantificável em mecanismo, não em reais.
3. **Mapear os direcionadores de custo de infraestrutura** — a arquitetura determina o formato da conta,
   mesmo sem saber o valor.
4. **Estimar o capital de engenharia até o lançamento** — em dias-pessoa, que é a unidade que o repositório suporta.

Onde há estimativa, ela está **marcada como estimativa e acompanhada da premissa**. Onde falta dado, o texto
diz o que precisa ser fornecido pela empresa. Nenhum número foi inventado para preencher tabela.

---

## 1. O modelo de negócio, como está efetivamente codificado

### 1.1 Catálogo comercial

Oito SKUs ativos (`016-monetization-catalog.yaml`):

| SKU | Tipo | Moedas | Preço |
|---|---|---|---|
| `brasil_drama_daily` | Assinatura | — | definido na Play Console |
| `brasil_drama_weekly` | Assinatura | — | definido na Play Console |
| `brasil_drama_monthly` | Assinatura | — | definido na Play Console |
| `brasil_drama_annual` | Assinatura | — | definido na Play Console |
| `brasil_drama_coins_100` | Consumível | 100 | definido na Play Console |
| `brasil_drama_coins_300` | Consumível | 300 | definido na Play Console |
| `brasil_drama_coins_700` | Consumível | 700 | definido na Play Console |
| `brasil_drama_coins_1500` | Consumível | 1.500 | definido na Play Console |

**Decisão de arquitetura com consequência financeira direta:** o preço **não** está no banco de dados. A
descrição dos produtos diz explicitamente *"O preço final é informado pela Google Play"*.

Isso está **certo** — evita divergência entre o preço exibido e o cobrado, e resolve moeda, imposto e
paridade de poder de compra de graça. Mas significa que **a estratégia de preço não é auditável a partir
deste repositório** e não pode ser analisada aqui. Para uma análise de margem, é preciso exportar os preços
da Play Console.

**Ponto de atenção comercial:** quatro degraus de assinatura (diário, semanal, mensal, anual) é bastante
para um lançamento. O plano diário é típico do gênero de microdramas e tende a canibalizar o mensal se a
diferença de preço não for calibrada. Vale um teste A/B depois do lançamento — o que exige a configuração
server-driven que hoje não existe (ver [01 — Arquitetura §4](01-VISAO-ARQUITETURA.md)).

### 1.2 Economia de moedas e pontos de vazamento

Três formas de obter moeda sem pagar:

| Fonte | Valor | Limite | Verificação |
|---|---|---|---|
| Bônus de boas-vindas | 100 moedas | 1 por conta | ⚠️ **sem verificação de e-mail** |
| Anúncio premiado | 20 moedas | 5/dia | ✅ SSV criptográfico |
| Check-in e missões | variável | por ciclo | ✅ server-authoritative |

Um episódio bloqueado custa 30 moedas no seed DEV (`db.changelog-master.yaml`).

**O bônus de boas-vindas é o ponto de vazamento financeiro do sistema.**

100 moedas ≈ 3 episódios pagos. Não há verificação de e-mail antes da concessão, e não há rate limit no
registro. A conta é direta: um script cria contas em série, cada uma recebendo 3 episódios de conteúdo pago.
Não há fraude sofisticada envolvida — é `curl` em laço.

O custo não é só o conteúdo liberado. É a **corrupção das métricas** que sustentam decisões comerciais: taxa
de conversão visitante→conta, ARPU, funil de aquisição. Todos os dez dashboards do Studio passam a medir
ruído junto com sinal, e não há como separar depois.

**Recomendação: verificação de e-mail antes de creditar o bônus, e rate limit no registro.** ~1 dia de
trabalho. É a correção de melhor relação custo/proteção do sistema inteiro.

### 1.3 Repasse à Google

Toda transação passa pelo faturamento da Google Play. A comissão padrão é 15% no primeiro milhão de dólares
anuais por desenvolvedor e 30% acima disso; assinaturas têm regime próprio.

**Implicação de planejamento que precisa estar no modelo financeiro:** a receita líquida é ~85% da receita
bruta na faixa inicial. Se o modelo da empresa foi construído sobre receita bruta, há um erro de 15% a
corrigir antes de qualquer projeção de caixa.

**Dado necessário da empresa:** o modelo de receita vigente considera a comissão? Em qual alíquota?

---

## 2. Receita em risco por defeito de código

Três defeitos com efeito financeiro direto. O mecanismo é fato verificado; a magnitude depende de volume,
que ainda não existe.

### F-01 — Compras não confirmadas são reembolsadas em 3 dias

**Mecanismo (verificado):** após a validação no servidor, o app chama `consume()` ou `acknowledge()`
(`PaywallScreen.kt:82-107`). Se a chamada falhar, a única resposta é a mensagem *"será tentada novamente"* —
mas **não há retentativa implementada**. Ela só ocorre se o usuário reabrir o paywall.
O servidor não serve de rede: nunca chama o endpoint de acknowledge da Google.

**Consequência:** a Google reembolsa automaticamente compras não confirmadas em 3 dias. O usuário pagou,
recebeu o benefício, e a receita é estornada.

**Exposição:** proporcional à taxa de falha de rede no instante da compra. Em rede móvel brasileira, o
momento imediatamente após a compra é justamente um ponto de instabilidade frequente. Não é um cenário de canto.

**Custo da correção:** ~4 horas. Confirmar no servidor logo após a validação, tornando o cliente redundante.

### F-02 — Anúncios não geram receita

**Mecanismo (verificado):** `AndroidManifest.xml:33` usa
`ca-app-pub-3940256099942544~3347511713` — o App ID **público de teste** do Google. E
`ADMOB_REWARDED_AD_UNIT_ID` tem default vazio no backend.

**Consequência:** em produção, o app serve anúncios de teste. Receita de anúncio: **zero**. Silenciosamente —
os anúncios aparecem, os usuários assistem, as moedas são creditadas, e nada é faturado.

**Exposição:** 100% da receita publicitária projetada.

Vale medir o peso disso: o anúncio premiado é o mecanismo que sustenta a monetização do usuário que não paga
— tipicamente a maioria absoluta da base num produto de microdramas. Se o modelo financeiro atribui receita
a esse canal, ela é integralmente nula até a correção.

**Custo da correção:** ~1 hora de código, depois que a conta AdMob de produção existir.

### F-03 — Emissão descontrolada de moedas

Detalhado em §1.2. Custo da correção: ~1 dia.

### Consolidado

| ID | Receita em risco | Correção | Prazo |
|---|---|---|---|
| F-01 | Fração das compras concluídas, estornada | Confirmação no servidor | 4 h |
| F-02 | **Toda** a receita de anúncio | ID de produção do AdMob | 1 h + conta |
| F-03 | Conteúdo pago liberado + métricas corrompidas | Verificação de e-mail + rate limit | 1 dia |

**Menos de dois dias de engenharia protegem os três.** Nenhum tem prazo externo além da criação da conta
AdMob. Não há justificativa de custo para adiar qualquer um deles.

---

## 3. Direcionadores de custo de infraestrutura

A arquitetura determina o formato da conta. Os componentes, verificados no código:

| Componente | Evidência | Comportamento do custo |
|---|---|---|
| Backend em container | `Dockerfile`, imagem no GHCR | Fixo por instância; escala em degraus |
| PostgreSQL | `application-dev-cloud.yml`, RDS | Fixo + armazenamento crescente |
| S3 `brasil-drama-dev-media` | `MediaStorageService` | **Cresce com o catálogo** — vídeo é o item pesado |
| Transferência de dados de vídeo | Presign de leitura, 60 min | 🔴 **Cresce com a audiência — direcionador dominante** |
| CloudFront (Studio) | `deploy-dev.yml` | Baixo; o Studio é estático e de baixo tráfego |
| Mailgun | `MailgunAdminPasswordResetMailer` | Por mensagem; baixo |
| FCM | Plano gratuito na prática | ~zero |

### O direcionador que domina tudo: entrega de vídeo

**Achado material:** o vídeo é servido por **URL S3 pré-assinada com validade de 60 minutos**
(`MEDIA_READ_PRESIGN_MINUTES:60`), **sem CDN na frente**.

Isso tem três consequências financeiras, em ordem de gravidade:

1. **Custo de saída do S3 é significativamente mais caro que o do CloudFront** para o mesmo volume, e a
   diferença cresce linearmente com a audiência.
2. **Zero cache.** Cada visualização do mesmo episódio popular é uma transferência nova do S3. Num produto
   de microdramas, onde poucos títulos concentram a maior parte das visualizações, isso é o pior caso possível
   de padrão de acesso — é exatamente onde um CDN pagaria por si em dias.
3. **Nenhum controle de custo.** Não há orçamento, alerta de gasto ou limite descrito em nenhum repositório.
   Um título viral produz uma fatura sem teto, descoberta no fechamento do mês.

**Recomendação: colocar CloudFront na frente da mídia antes do lançamento.** É o item de maior impacto
financeiro em toda esta análise, e é infraestrutura, não código de aplicação — o backend continua emitindo
URL assinada, apenas passa a assinar para o domínio do CloudFront.

Ordem de grandeza da economia em vídeo com cache eficaz: **substancial, e crescente com o volume.** Não
estimo percentual porque depende da taxa de acerto de cache, que depende da distribuição de audiência —
dado que não existe ainda.

**Dados necessários da empresa para dimensionar a conta:**
- Usuários ativos diários projetados nos meses 1, 3 e 6
- Minutos assistidos por usuário por dia
- Bitrate médio dos vídeos e tamanho do catálogo em GB
- Distribuição geográfica (afeta a escolha das edge locations)

Com esses quatro números, o custo de transferência é aritmética direta. Sem eles, qualquer valor seria invenção.

---

## 4. Capital de engenharia até o lançamento

Unidade: **dias-pessoa**, derivados do esforço estimado por item nos documentos 02 e 06.

| Fase | Escopo | Dias-pessoa | Paralelizável |
|---|---|---|---|
| F0 — Destravar | Build local, CI Android, lockfile, seed DEV | 4 | Parcialmente |
| F1 — RC1 em DEV | Escopo do plano existente | 12 | Sim (3 frentes) |
| F2 — Conformidade | Exclusão de conta, privacidade, Data Safety, LGPD | 8 | Parcialmente |
| F3 — Produção | Ambiente PROD, build assinado, observabilidade, runbook | 14 | Sim |
| F4 — Loja | Submissão, correções de revisão | 4 | Não |
| **Total** | | **42 dias-pessoa** | |

**Prazo em calendário** (estimativa, premissa: 3 pessoas trabalhando com sobreposição parcial):
**5 a 7 semanas**, das quais 2 a 3 são espera externa que não acelera com mais pessoas:

- Revisão da Google Play: 1 a 7 dias por submissão, e a primeira raramente passa
- Verificação da conta de desenvolvedor: dias a semanas
- Propagação de DNS e emissão de certificados: horas a dias
- Aprovação da conta AdMob: dias

**Consequência de planejamento:** adicionar pessoas reduz F1 e F3, e não move F4. A data de lançamento é
limitada por prazo externo tanto quanto por capacidade. Contratar para acelerar tem retorno decrescente
depois de ~4 pessoas.

**Custo não estimado aqui:** valor-dia de engenharia é dado da empresa. Multiplicar 42 pelo custo interno
carregado dá o capital de engenharia direto até o lançamento.

---

## 5. Custos recorrentes que precisam entrar no orçamento

Nenhum destes aparece em documento algum do projeto. Todos são obrigatórios ou fortemente recomendados:

| Item | Natureza | Situação |
|---|---|---|
| Conta de desenvolvedor Google Play | Taxa única | Não verificável |
| Infraestrutura AWS de produção | Mensal, escala com uso | **Não orçada** |
| Infraestrutura AWS de DEV (existente) | Mensal | Não visível neste dossiê |
| Rastreio de erro (Sentry ou equivalente) | Mensal | **Não contratado** — R5 depende disto |
| Monitoramento e alerta | Mensal | **Não contratado** |
| Mailgun | Por volume | Ativo |
| Runner auto-hospedado do Mobile Center | Hardware ou nuvem, 16–32 GB RAM + KVM | **Não provisionado** |
| Domínio `brasildrama.com.br` + certificados | Anual | Referenciado no código; posse não verificável |
| Assessoria jurídica (LGPD, termos, privacidade) | Pontual | **Não contratada** |

**Os dois de maior risco orçamentário são os menos óbvios:** a saída de dados de vídeo (§3) e a assessoria
jurídica. O primeiro porque não tem teto; o segundo porque não tem substituto — e sem ele os documentos de
conformidade não existem, e sem eles não há lançamento.

---

## 6. Onde o dinheiro já investido está parado

Trabalho concluído que não gera valor por falta de um passo pequeno. Do ponto de vista financeiro, é capital
imobilizado:

| Ativo | Investimento | Bloqueio | Custo para destravar |
|---|---|---|---|
| Mobile Center — lab Appium, matriz API 32–35, noVNC, relatórios | Alto | Variável de repositório desligada + runner ausente | 2 dias + hardware |
| Dez dashboards de analytics no Studio | Alto | Sem dados reais em DEV (seed insuficiente) | 1 dia (o seed) |
| Modo maratona (E06), integrado e testado | Médio | Inatingível em DEV — exige 3 avanços, série mais longa tem 2 episódios | Incluído no seed |
| Integração AdMob completa, com SSV criptográfico | Médio | ID de teste no manifesto | 1 hora |
| Validação de compra Google Play, correta e idempotente | Alto | Sem SKU na Play Console, sem conta de pagamento | Externo |
| Push FCM ponta a ponta | Médio | Firebase vazio em `hml`/`prod` | 2 horas |

**Leitura financeira:** há uma quantidade desproporcional de trabalho de alta qualidade a uma ou duas horas
de virar valor. O padrão se repete o suficiente para ser um achado organizacional, não uma coincidência —
o time é forte em construir e fraco em **encerrar**. A última milha de cada entrega fica aberta.

Isso não se resolve com mais engenharia. Resolve-se com uma definição de pronto que inclua "está ligado e
gerando valor em um ambiente real" — que, aliás, é exatamente o que o próprio
`RC2_PRODUCT_EXPERIENCE_CONTRACT.md` já determina e que não vinha sendo aplicado.

---

## 7. Recomendações financeiras

**Imediatas — custo desprezível, retorno direto:**

1. Corrigir F-01, F-02 e F-03. **Menos de 2 dias-pessoa protegem toda a receita transacional e publicitária.**
   Não há argumento de custo para adiar.
2. Colocar CloudFront na frente da mídia antes de qualquer tráfego real. É o maior direcionador de custo do
   sistema e a arquitetura atual é o pior caso.
3. Configurar alerta de orçamento na AWS. Custa zero e é a única proteção contra uma fatura surpresa.

**Antes do lançamento:**

4. Orçar a infraestrutura de produção com os quatro dados de audiência do §3. Hoje não há número algum.
5. Contratar rastreio de erro e monitoramento. Barato, e é o que evita o incidente descoberto pela avaliação
   de uma estrela.
6. Contratar a assessoria jurídica de LGPD. É caminho crítico: sem os documentos, não há submissão.

**Decisões que pedem posição do CEO/CFO:**

7. **Lançar com ou sem monetização?** Sem monetização, o caminho até a loja encurta em 1 a 2 semanas e a
   revisão é mais simples. Com monetização, há receita desde o dia 1 e mais risco de prazo. O código suporta
   as duas — desligar é configuração.
8. **Quatro degraus de assinatura no lançamento, ou dois?** Menos SKUs significa menos configuração na Play
   Console, menos superfície de teste e um funil mais legível para calibrar depois.
9. **Qual é o orçamento mensal máximo de infraestrutura?** Sem esse teto definido, não há como dimensionar
   nem como saber se um pico de audiência é boa notícia.

---

## 8. Veredito financeiro

O produto tem **mecânica de receita correta e bem construída** — validação de compra idempotente,
verificação criptográfica de recompensa, estado autoritativo no servidor. A infraestrutura financeira do
código está entre as partes mais bem-feitas do projeto.

Três defeitos comprometem essa receita, e os três custam junto **menos de dois dias** para corrigir. Um
deles zera integralmente o canal publicitário.

O risco financeiro maior não está no código: está no **custo de saída de vídeo sem CDN e sem teto**,
combinado com a ausência de qualquer orçamento de infraestrutura. É a única exposição do projeto que pode
crescer sem limite e sem aviso — e o sucesso do produto é justamente o gatilho.

E há um custo que ainda não está na conta de ninguém: **as 42 dias-pessoa entre o estado atual e a loja.**
Elas existem quer sejam planejadas ou não. Planejadas, cabem em 5 a 7 semanas. Não planejadas, aparecem como
atraso de prazo, corte de escopo sob pressão, ou rejeição na revisão da Google.
