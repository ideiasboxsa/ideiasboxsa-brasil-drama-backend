# Brasil Drama — Dossiê de Avaliação do Produto

Análise independente das três frentes do ecossistema Brasil Drama, conduzida em **2026-08-31** sob quatro
perspectivas: **Arquitetura**, **Engenharia**, **CTO** e **Financeira (CFO)**.

## Por que este dossiê existe

Já existe um plano de finalização — `ideiasboxsa-brasil-drama-backend/docs/PLANO_FINALIZACAO_RC1.md`,
criado em 2026-08-30. Ele é bom e continua válido. Mas ele responde a uma pergunta específica:
**"o que falta para fechar o RC1 em DEV?"**

A pergunta feita aqui é outra: **"o que falta para lançar o produto?"**

A distância entre as duas é maior do que qualquer documento do projeto registra hoje. O RC1 é um marco de
homologação em ambiente de desenvolvimento. O lançamento exige ambiente de produção, aprovação na Google Play,
conformidade com a LGPD e capacidade de operar o serviço — nenhum dos quatro tem trabalho iniciado.

Este dossiê mede as duas distâncias separadamente e não mistura as contas.

## Índice

| Documento | Perspectiva | Responde |
|---|---|---|
| [00 — Sumário Executivo](00-SUMARIO-EXECUTIVO.md) | CEO / Board | Onde estamos, quando lançamos, o que decidir agora |
| [01 — Visão de Arquitetura](01-VISAO-ARQUITETURA.md) | Arquiteto | O desenho aguenta o produto pretendido? |
| [02 — Visão de Engenharia](02-VISAO-ENGENHARIA.md) | Engenheiro | O código está correto, testado e entregável? |
| [03 — Visão de CTO](03-VISAO-CTO.md) | CTO | Risco, segurança, conformidade, operação, time |
| [04 — Visão Financeira](04-VISAO-FINANCEIRA.md) | CFO | Custo, receita em risco, unit economics, capital até o lançamento |
| [05 — Matriz de Entregas e Lacunas](05-MATRIZ-ENTREGAS-E-GAPS.md) | Todos | O que existe, o que não existe, evidência por item |
| [06 — Caminho para o Lançamento](06-CAMINHO-PARA-LANCAMENTO.md) | Todos | Plano executável, do estado atual à loja |

## Onde este dossiê vive

`/data/IdeaProjects/brasil-drama` é um diretório contêiner, **não** um repositório
Git — arquivos criados aqui não são versionados.

Por isso a cópia canônica e versionada está em
**`ideiasboxsa-brasil-drama-backend/docs/avaliacao-produto/`**, seguindo a mesma
convenção já usada pelo `PLANO_FINALIZACAO_RC1.md`. Esta pasta na raiz é a visão
de trabalho entre as três frentes; ao editar, atualize a cópia versionada.

## Como ler

Cada afirmação técnica neste dossiê aponta para o arquivo e a linha que a sustentam. Onde não foi possível
verificar (infraestrutura, contas de terceiros, dados de negócio), o texto diz explicitamente
**"não verificável a partir do repositório"** em vez de estimar.

## Escopo da inspeção

Três repositórios, branch `develop`, em 2026-08-31:

| Repositório | HEAD | Stack |
|---|---|---|
| `brasil-drama-android` | `f054a2b` | Kotlin, Compose, Media3 |
| `ideiasboxsa-brasil-drama-backend` | `9f70446` | Java 21, Spring Boot 3.3.13 |
| `brasil-drama-admin` | `be38765` | Next.js 15, React 19 |

Não foram inspecionados: infraestrutura AWS em execução, Google Play Console, AdMob, Firebase Console,
banco de dados DEV em runtime, e nenhum dado financeiro real da operação.
