# Brasil Drama RC2 — Discovery & Recommendation Contract

Status: IMPLEMENTATION STARTED

## Objective

Move discovery from a static catalog model to a server-driven short-drama discovery platform without breaking RC1 contracts.

## Signals V1

The recommendation domain will converge on these behavioral signals:

- IMPRESSION
- PLAY
- WATCH_3S
- WATCH_25
- WATCH_50
- WATCH_75
- COMPLETE
- SKIP
- ABANDON
- NEXT_EPISODE
- LIKE / UNLIKE
- FAVORITE / UNFAVORITE
- SHARE
- SEARCH
- REWARD_INTERACTION
- BINGE_SESSION
- NOT_INTERESTED

All ingestion must support guest identity and authenticated user identity, preserve idempotency where a client event id is supplied, and never block playback for analytics failure.

## Recommendation surfaces

Initial server-driven surfaces:

- FOR_YOU
- CONTINUE_WATCHING
- TOP_10_BRASIL
- TRENDING_NOW
- NEW_RELEASES
- GENRE_AFFINITY
- NEXT_OBSESSION

Cold start falls back to freshness + trending + editorial availability. Ranking must allow diversity so a single genre/tag cannot monopolize the feed.

## Affinity V1

Inputs:

- genre affinity
- tag affinity
- completion rate
- next-episode behavior
- binge depth
- likes/favorites
- recency
- popularity/trend velocity
- explicit negative feedback

Negative feedback and rapid skips reduce affinity. Scores decay over time.

## API direction

Public/mobile APIs will expose server-driven rails and ranked drama identifiers while preserving existing catalog endpoints as fallback. Android must not reproduce ranking weights locally.

## Studio direction

Studio will expose operational visibility and configuration for recommendation weights/surfaces only after backend contracts are stable. Product analytics must include hook rate, episode completion, next-episode rate, binge depth, series completion, drop-off episode, guest-to-account conversion and free-to-VIP conversion.

## Compatibility

RC1 APIs remain compatible. No asset concern belongs to this backend epic.
