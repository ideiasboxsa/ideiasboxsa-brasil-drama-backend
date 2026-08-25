# Brasil Drama Backend — DEV Cloud

O ambiente DEV público deve executar o backend com o profile `dev-cloud` e banco PostgreSQL dedicado.

## Runtime

- Java 21
- PostgreSQL 16+
- Container construído pelo `Dockerfile` da raiz
- Porta: variável `PORT` (fallback 8080)
- Healthcheck: `GET /actuator/health`
- Spring profile: `SPRING_PROFILES_ACTIVE=dev-cloud`

## Variáveis obrigatórias

```text
SPRING_PROFILES_ACTIVE=dev-cloud
DB_URL=jdbc:postgresql://<host>:5432/<database>?sslmode=require
DB_USER=<user>
DB_PASSWORD=<secret>
JWT_SECRET=<random-secret-with-at-least-32-bytes>
```

Não usar os defaults locais em cloud. `JWT_SECRET` deve ser gerado fora do repositório e armazenado no secret manager do provedor.

## Variáveis recomendadas para DEV

```text
LIQUIBASE_CONTEXTS=base,dev
JWT_TTL=PT24H
REWARDS_ZONE_ID=America/Sao_Paulo
REWARDS_WELCOME_ENABLED=true
REWARDS_WELCOME_BONUS=50
REWARDED_AD_DAILY_LIMIT=5
REWARDED_AD_BONUS=20
REWARDED_AD_SESSION_TTL_MINUTES=10
ADMOB_REWARDED_AD_UNIT_ID=<dev-or-test-ad-unit-id>
JAVA_OPTS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError
```

O contexto Liquibase `dev` contém apenas catálogo/missões técnicas de desenvolvimento. Nunca habilitar `dev` em HML/PROD.

## AdMob SSV

Depois que a URL HTTPS pública existir, configurar no AdMob o callback:

```text
https://<api-dev-host>/v1/rewards/ads/ssv
```

O endpoint é público por necessidade do Google, mas a recompensa só é aceita quando a assinatura SSV é válida e o `custom_data` corresponde a uma sessão emitida pelo backend para um usuário autenticado.

## Smoke test pós-deploy

1. `GET /actuator/health` deve retornar `UP`.
2. `GET /v1/home` deve responder sem autenticação.
3. `POST /v1/auth/register` deve criar conta e retornar JWT.
4. `GET /v1/rewards/overview` com Bearer token deve refletir o bônus de boas-vindas configurado.
5. `GET /v1/catalog/dramas/{id}` deve retornar episódios do seed DEV.
6. O APK DEV deve usar a URL HTTPS pública como `API_BASE_URL`.

## Segurança

- Não versionar credenciais, URLs com senha ou JWT secrets.
- Banco DEV não deve ser compartilhado com HML/PROD.
- Usar TLS entre aplicação e PostgreSQL quando o provedor suportar/obrigar.
- Manter `/actuator` limitado a `health,info`.
- O container roda com usuário não-root.
