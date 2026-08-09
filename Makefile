SHELL := /bin/bash

.PHONY: verify run-offline run docker-up clean

verify:
	mvn --batch-mode clean verify

run-offline:
	mkdir -p data
	LEETCODE_REMOTE_ENABLED=false mvn spring-boot:run

run:
	mkdir -p data
	mvn spring-boot:run

docker-up:
	docker compose up --build

clean:
	mvn clean
