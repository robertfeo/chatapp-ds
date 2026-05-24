# chatapp-ds developer entry points. Thin wrappers around Maven so every team
# member runs the same commands regardless of IDE.

.PHONY: install lint format test package dev stop

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

# Spin up 3 servers + 2 clients on localhost. Wired up in #2 (local dev runner).
dev:
	@echo "dev runner not implemented yet (see issue #2)"

# Tear down everything started by 'make dev'. Wired up in #2.
stop:
	@echo "stop not implemented yet (see issue #2)"
