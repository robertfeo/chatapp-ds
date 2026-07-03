# chatapp-ds developer entry points. Thin wrappers around Maven so every team
# member runs the same commands regardless of IDE.

.PHONY: install lint format test package server client

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

# Run a server / a client in the foreground. Builds the jar first if it is
# missing; after changing sources, run 'make package' to rebuild it.
server: target/chatapp.jar
	java -jar target/chatapp.jar server

client: target/chatapp.jar
	java -jar target/chatapp.jar client

target/chatapp.jar:
	mvn -DskipTests package
