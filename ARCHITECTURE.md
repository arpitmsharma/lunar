# Architecture Documentation
---

## Design Principles

The Lunar Rockets Service is built on core architectural principles that guide all technical decisions.

### 1. Clean Architecture

**Dependency Rule:** Dependencies point inward toward the domain. External layers depend on internal layers, never the reverse.

```
┌──────────────────────────────────────────────────┐
│         Infrastructure Layer (Ktor, DB)          │
│  ┌────────────────────────────────────────────┐  │
│  │      Interface Adapters (Repositories)     │  │
│  │  ┌──────────────────────────────────────┐  │  │
│  │  │     Business Logic (Service)         │  │  │
│  │  │  ┌────────────────────────────────┐  │  │  │
│  │  │  │   Domain Model (Entities)      │  │  │  │
│  │  │  │   • RocketState                │  │  │  │
│  │  │  │   • RocketEvent                │  │  │  │
│  │  │  │   • RocketStatus               │  │  │  │
│  │  │  └────────────────────────────────┘  │  │  │
│  │  └──────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────┘

Dependencies flow: Outer → Inner
```

**Benefits:**
- Domain logic independent of frameworks
- Easy to test (mock outer layers)
- Can swap infrastructure without touching business logic
- Clear separation of concerns

### 2. Single Responsibility Principle

Each component has exactly one reason to change:

```
┌─────────────────┐
│  RocketRoutes   │  Changes when: API contract changes
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  RocketService  │  Changes when: Business rules change
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Repository     │  Changes when: Storage mechanism changes
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   Database      │  Changes when: Schema evolves
└─────────────────┘
```

### 3. Type Safety Over Runtime Checks

The system leverages Kotlin's type system to catch errors at compile time:

**Strong Types:**
- `UUID` for channel identifiers (not String)
- `RocketStatus` enum for status (not String)
- Sealed interfaces for events (exhaustive when expressions)

**Benefits:**
- Impossible to create invalid states
- IDE autocomplete for valid values
- Compiler enforces correctness
- Runtime errors become compile errors

### 4. Tell, Don't Ask (Command Pattern)

Events apply themselves to state rather than having external code query and modify state:

```
Event-Driven State Transitions
═══════════════════════════════

┌──────────────┐
│ RocketEvent  │
│  (Command)   │
└──────┬───────┘
       │ applyTo(state)
       ▼
┌──────────────┐      ┌──────────────┐
│ Current      │  →   │ New          │
│ RocketState  │      │ RocketState  │
└──────────────┘      └──────────────┘

Each event encapsulates its transformation logic
No external code needs to know how to apply events
```

---

## System Architecture

### High-Level Component View

```
                    HTTP Requests
                         │
                         ▼
        ┌────────────────────────────────┐
        │      HTTP Layer (Ktor)         │
        │    • Routes                    │
        │    • JSON Serialization        │
        │    • Request Validation        │
        └────────────┬───────────────────┘
                     │
                     ▼
        ┌────────────────────────────────┐
        │      Service Layer             │
        │    • Business Logic            │
        │    • Message Sequencing        │
        │    • Event Application         │
        └────────┬───────────────────────┘
                 │
                 ▼
        ┌────────────────────────────────┐
        │   Repository Interfaces        │
        │    • RocketRepository          │
        │    • StashRepository           │
        │    • TransactionRunner         │
        └────────┬───────────────────────┘
                 │
                 ▼
        ┌────────────────────────────────┐
        │  Repository Implementations    │
        │    • Exposed SQL               │
        │    • Database Access           │
        └────────┬───────────────────────┘
                 │
                 ▼
        ┌────────────────────────────────┐
        │       PostgreSQL               │
        │    • Rockets Table             │
        │    • Stash Table               │
        └────────────────────────────────┘
```

## Out-of-Order Message Processing

### The Problem

Rocket telemetry arrives over unreliable networks with varying delays:

```
Expected Sequence:  [1] → [2] → [3] → [4] → [5]
                     ↓     ↓     ↓     ↓     ↓
Actual Arrival:     [1] → [3] → [5] → [2] → [4]
                           ↑     ↑           ↑
                        Out of order messages
```

**Requirement:** Process messages in sequential order regardless of arrival order.

### Solution: Stash-and-Drain Algorithm

#### State Machine

```
                  Message Arrives
                       │
                       ▼
        ┌──────────────────────────────┐
        │  Compare with lastApplied    │
        └──────────┬───────────────────┘
                   │
      ┌────────────┼────────────┐
      │            │            │
      ▼            ▼            ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│msg <= N  │ │msg = N+1 │ │msg > N+1 │
│          │ │          │ │          │
│ IGNORE   │ │  APPLY   │ │  STASH   │
│ (dupe)   │ │          │ │          │
└──────────┘ └────┬─────┘ └──────────┘
                  │
                  ▼
           ┌──────────────┐
           │ Check Stash  │
           │ for next     │
           │ sequence     │
           └──────┬───────┘
                  │
         ┌────────┴────────┐
         │                 │
         ▼                 ▼
    ┌─────────┐      ┌─────────┐
    │ Found   │      │Not Found│
    │ DRAIN   │      │  DONE   │
    └────┬────┘      └─────────┘
         │
         └─── Loop back to apply next
```

#### Drain Loop Flow

```
                Apply Message N
                      │
                      ▼
              Update lastApplied = N
                      │
                      ▼
              Save Rocket State
                      │
                      ▼
         Query Stash for Message N+1
                      │
              ┌───────┴───────┐
              │               │
              ▼               ▼
         ┌─────────┐    ┌─────────┐
         │ Found   │    │Not Found│
         └────┬────┘    └────┬────┘
              │              │
              ▼              ▼
       Parse Stashed    Delete Drained
       Message          Messages
              │              │
              ▼              ▼
       N = N + 1         COMPLETE
              │
              └─── Loop back to Apply
```

#### Storage Model

**Stash Table:**

```
┌──────────────┬────────────────┬─────────────────┐
│  channel_id  │ message_number │    payload      │
├──────────────┼────────────────┼─────────────────┤
│  UUID-1      │       3        │  {JSON event}   │
│  UUID-1      │       5        │  {JSON event}   │
│  UUID-2      │       7        │  {JSON event}   │
└──────────────┴────────────────┴─────────────────┘
         │              │
         └──────┬───────┘
                │
    Composite Primary Key
    (ensures uniqueness per channel)
```

**Properties:**
- Indexed by (channel_id, message_number)
- Messages removed after successful drain
- Different channels are independent
- No size limit (monitor in production)

---

## Concurrency & Transaction Safety

### The Race Condition Problem

Without proper locking, concurrent requests can cause data corruption:

```
Timeline: Two threads processing same channel

T1  Thread A                    Thread B
│   ┌──────────────────┐
│   │ Read: last=5     │
│   └─────────┬────────┘
│             │
T2  ┌─────────▼────────┐        ┌──────────────────┐
│   │ Process msg 6    │        │ Read: last=5     │
│   └──────────────────┘        └─────────┬────────┘
│                                          │
T3                               ┌─────────▼────────┐
│                                │ Process msg 6    │
│                                │ (DUPLICATE!)     │
│                                └──────────────────┘
│             │                            │
T4  ┌─────────▼────────┐        ┌─────────▼────────┐
│   │ Write: last=6    │        │ Write: last=6    │
│   └──────────────────┘        └──────────────────┘
│
▼
    Result: Message 6 applied twice (INCORRECT!)
```

### Solution: Pessimistic Locking

**Mechanism:** Database row-level locks (SELECT FOR UPDATE)

```
Timeline: With pessimistic locking

T1  Thread A                    Thread B
│   ┌──────────────────┐
│   │ SELECT FOR       │
│   │ UPDATE           │
│   │ (LOCK ACQUIRED)  │
│   └─────────┬────────┘
│             │
T2            │                 ┌──────────────────┐
│             │                 │ SELECT FOR       │
│   ┌─────────▼────────┐        │ UPDATE           │
│   │ Process msg 6    │        │ (BLOCKED -       │
│   │ Write: last=6    │        │  waiting...)     │
│   └─────────┬────────┘        └──────────────────┘
│             │                            ⏸
T3  ┌─────────▼────────┐                  │
│   │ COMMIT           │                  │
│   │ (LOCK RELEASED)  │                  │
│   └──────────────────┘                  │
│                                          ▼
T4                               ┌──────────────────┐
│                                │ (LOCK ACQUIRED)  │
│                                │ Read: last=6     │
│                                │ Msg 6 is dupe    │
│                                │ IGNORE           │
│                                └─────────┬────────┘
│                                          │
T5                               ┌─────────▼────────┐
│                                │ COMMIT           │
│                                └──────────────────┘
▼
    Result: Message 6 applied once (CORRECT!)
```

### Performance Implications

**Throughput:**

```
Single Channel Processing:
┌────┐  ┌────┐  ┌────┐  ┌────┐
│Msg1│→ │Msg2│→ │Msg3│→ │Msg4│  Sequential
└────┘  └────┘  └────┘  └────┘
  ↓       ↓       ↓       ↓
 Lock → Lock → Lock → Lock

Multi-Channel Processing:
┌────────────┐  ┌────────────┐
│ Channel A  │  │ Channel B  │
│ Msg1→Msg2  │  │ Msg1→Msg2  │  Parallel!
└────────────┘  └────────────┘
      ↓                ↓
  Lock on A       Lock on B
```

**Characteristics:**
- Single channel: ~1000 msg/sec (serialized)
- Different channels: Fully parallel
- Correctness over throughput

---


### Sealed Interface for Events

**Design Decision:** Sealed interface instead of class hierarchy

```
sealed interface RocketEvent
    │
    ├─→ Launched
    ├─→ SpeedIncreased
    ├─→ SpeedDecreased
    ├─→ MissionChanged
    └─→ Exploded

┌─────────────────────────────────────────┐
│              Benefits                   │
├─────────────────────────────────────────┤
│ ✓ Exhaustive when expressions           │
│ ✓ Each event has specific fields        │
│ ✓ Polymorphism (each implements apply)  │
│ ✓ Closed set (no subclasses outside)    │
│ ✓ Pattern matching safety               │
└─────────────────────────────────────────┘
```
