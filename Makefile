.PHONY: up down restart logs clean test build

# Single command to build and run everything
up:
	docker-compose up -d --build

# Stop all services
down:
	docker-compose down

# Restart services
restart:
	docker-compose restart

# View logs
logs:
	docker-compose logs -f app

# Stop and remove everything (including volumes)
clean:
	docker-compose down -v

# Run tests locally
test:
	./gradlew test

# Build locally (without Docker)
build:
	./gradlew build
