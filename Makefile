# chatapp-ds developer entry points. Thin wrappers around Maven so every team
# member runs the same commands regardless of IDE.

.PHONY: install lint format test package dev stop status

# Build and install to the local repo without running tests.
install:
	mvn -DskipTests install

# Verify Google Java Format compliance (fails the build on violations).
lint:
	mvn spotless:check

# Auto-apply Google Java Format.
format:
	mvn spotless:apply

# Run the JUnit test suite.
test:
	mvn test

# Produce the shaded fat-jar at target/chatapp.jar.
package:
	mvn package

# Spin up 3 servers + 2 clients on localhost (background JVMs, logs in target/dev/).
# Override counts/ports with env vars, e.g. SERVERS=3 CLIENTS=2 make dev.
dev:
	@bash scripts/dev.sh up

# Tear down everything started by 'make dev', leaving no orphan processes.
stop:
	@bash scripts/dev.sh down

# Show which dev processes are alive.
status:
	@bash scripts/dev.sh status
