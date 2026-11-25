# Preferred brain recovery

Phone 0.15.77, 25 November 2026. Companion desktop/node release 0.2.145.

The old chooser sorted a tied priority by display name while desktop Settings sorted it by pairing time. `BrainRef` now carries optional pairing time, persisted in the link and received from newer nodes. The chooser and both device screens use priority, pairing time, then id. Status explicitly flags a remaining tie.

An open socket on mobile data previously skipped probes even after a turn-finished kick. The phone now rechecks a higher-ranked brain on the next idle message, a device health event and the existing between-turn kick. Selection and message delivery are serialized. An in-flight send or main turn stays on its current brain.

A deliberate preferred-brain recheck probes it despite the old unanswered-message cooldown. A positive ready response clears that cooldown; a negative probe retains it. Ordinary failover retries still honor the cooldown. Message ids, durable acknowledgement and duplicate suppression are unchanged.

Connections > Your machines and Settings > Linked devices show the connected brain and why it is answering. Disk warnings from the current node are included in Connections. An older node without pairing time remains readable and is ordered deterministically by id when all supplied ranks/times tie.

Proof: the pairing-order regression failed with the old name-based comparator. Final RemoteLinkTest and SendFlowTest passed 39/39. The desktop/node companion also proved handback between two isolated nodes after suspending and resuming only the preferred node; no real device order or leases were edited for that test. APK installation is a separate approved action and was not performed.
