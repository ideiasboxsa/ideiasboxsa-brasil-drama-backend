# Brasil Drama Backend

Backend server-authoritative do Brasil Drama.

## Stack
- Java 21
- Spring Boot
- PostgreSQL
- Liquibase
- Spring Security
- Actuator
- Docker / Docker Compose

## Branches
- `main`: produção
- `develop`: integração/DEV
- `feature/*`: histórias

## Contrato mobile
A API deve permanecer compatível com o Retrofit definido em `brasil-drama-android` (`BrasilDramaApi.kt`).

## Segurança
Segredos, tokens, chaves Google Play e credenciais de banco nunca devem ser versionados. Use variáveis de ambiente/GitHub Secrets.
