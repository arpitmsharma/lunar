# Lunar Rockets Service

Real-time rocket telemetry processing with out-of-order message handling.

Simple Architecture: `main` branch
Modular Architecture: `modular-async-architecture` branch

## Running with Docker Compose

### Prerequisites
- Docker and Docker Compose installed
- No other services running on ports 5432 (PostgreSQL) or 8088 (API)

### Start the Application

```bash
docker-compose up -d --build
```

This command will:
1. Build the Kotlin/Ktor application using multi-stage Docker build
2. Start PostgreSQL 15 (port 5432) with health checks
3. Start the API server (port 8088) once PostgreSQL is ready
4. Create and initialize the database schema automatically

Wait ~30 seconds for services to start. Check status:
```bash
docker-compose ps
```

Both services should show "Up" and postgres should be "healthy".

### View Logs

```bash
docker-compose logs -f app
```

### Test the API

**Send a rocket launch message:**
```bash
curl -X POST http://localhost:8088/messages \
  -H "Content-Type: application/json" \
  -d '{
    "metadata": {
      "channel": "550e8400-e29b-41d4-a716-446655440000",
      "messageNumber": 1,
      "messageTime": "2024-01-01T10:00:00Z",
      "messageType": "RocketLaunched"
    },
    "message": {
      "type": "Falcon-9",
      "launchSpeed": 500,
      "mission": "ARTEMIS"
    }
  }'
```

**Query rocket state:**
```bash
curl http://localhost:8088/rockets/550e8400-e29b-41d4-a716-446655440000
```

**List all rockets:**
```bash
curl http://localhost:8088/rockets
```

### Stop Services

**Stop services (preserves data):**
```bash
docker-compose down
```

**Stop and remove everything (including database data):**
```bash
docker-compose down -v
```

## API Endpoints

- `POST /messages` - Send telemetry (RocketLaunched, RocketSpeedIncreased, RocketSpeedDecreased, RocketMissionChanged, RocketExploded)
- `GET /rockets/{id}` - Get rocket state
- `GET /rockets?sort={field}&order={asc|desc}` - List all rockets

## Architecture

**Key Features:**
- Out-of-order message handling (stash-and-drain algorithm)
- UUID channel identifiers + enum status (LAUNCHED, EXPLODED)
- PostgreSQL with pessimistic locking (SELECT FOR UPDATE)
- Kotlin + Ktor + Exposed ORM

**See:** [ARCHITECTURE.md](./ARCHITECTURE.md) for technical details

## Development

```bash
# Build and test
./gradlew build
./gradlew test

# Run locally
./gradlew run
```

## Environment Variables

| Variable | Default |
|----------|---------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/lunar` |
| `DATABASE_USER` | `lunar` |
| `DATABASE_PASSWORD` | `lunar` |

---

**Built with Kotlin + Ktor**
