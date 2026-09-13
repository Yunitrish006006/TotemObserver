# World section scheduler

The section scheduler is a client-side control layer above `world_section` v1. It does not change the wire protocol and it does not enable gameplay.

## Ownership

A `WorldSectionScheduler` owns section request sequencing for one `ObserverConnection`. Callers enqueue desired coordinates through the scheduler instead of issuing concurrent `requestWorldSection` calls directly.

The current wire contract is single-flight, so the scheduler sends exactly one section request at a time and advances only after the requested section is present in the connection cache.

## Bounds

The scheduler accepts only coordinates inside the current `world_bootstrap` horizontal radius and vertical dimension bounds. It stores at most 64 queued coordinates.

`enqueueHorizontalWindow(sectionY)` expands the current bootstrap window center-first using Manhattan distance. For the current radius 2 contract this represents at most 25 chunks for one vertical section.

## Revision safety

Queued and active work is scoped to the current `(subscriptionId, revision)`. Any bootstrap subscription/revision change clears outstanding scheduler work before new coordinates may be queued. Completed section snapshots remain owned by `ObserverConnection`, whose cache is already invalidated by bootstrap resync.

## Non-goals

This scheduler does not automatically fetch every vertical section, render blocks, stream live block deltas, fetch lighting/biomes/entities, or change `play:false`. A future view/cache policy decides which vertical sections are actually needed.
