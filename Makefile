.PHONY: up down build test logs chaos

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

# 混沌测试：需完整栈已 up；对账参数加速时先 RECONCILE_STALE_MINUTES=1 RECONCILE_INTERVAL_MS=15000 make up
chaos:
	bash scripts/chaos-test.sh 3 12
