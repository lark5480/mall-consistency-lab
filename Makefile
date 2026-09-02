.PHONY: up down build test logs

up:
	docker compose up -d --build

down:
	docker compose down

build:
	mvn clean package -DskipTests

test:
	mvn test

logs:
	docker compose logs -f --tail=100
