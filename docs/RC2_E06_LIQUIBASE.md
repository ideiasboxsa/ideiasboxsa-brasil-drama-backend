# Liquibase E06

Não há migration em E06. Os eventos `next_episode` e `binge_session` reutilizam `playback_event`; as métricas são agregadas sobre dados existentes. Qualquer futura necessidade de schema deve ser tratada em mudança separada e explícita.
