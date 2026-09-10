# TotemObserver

Dedicated Observer View runtime for the Totem Minecraft ecosystem.

This module owns the server-authoritative, spectator-only, read-only Observer session and semantic-screen transport. Feature modules continue to own their own `ObserverScreenProvider` implementations through TotemCore contracts.

## Runtime invariants

- no framebuffer, screenshot, or video transport
- server-authoritative session and permission checks
- spectator-only observers
- read-only reconstructed UI
- bounded semantic payloads and monotonic sequences
- privacy-sensitive owner payloads remain the responsibility of the owning provider
- unsupported optional providers fail closed without taking down the Observer session

Minecraft 26.2 / Java 25 / Fabric.
