#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Build
echo "=== Building project ==="
cd "$PROJECT_ROOT"
mvn package -DskipTests -q

AGENT_JAR="$PROJECT_ROOT/VirtualThread-Scheduler-Agent/target/VirtualThread-Scheduler-Agent-1.0-SNAPSHOT.jar"
APP_JAR="$SCRIPT_DIR/target/VirtualThread-Scheduler-Example-1.0-SNAPSHOT.jar"

AGENT_ARGS="jdk.virtualThreadScheduler.poller.implClass=io.github.dreamlike.scheduler.example.CustomerVirtualThreadRuntime"
AGENT_ARGS="$AGENT_ARGS,jdk.virtualThreadScheduler.poller.dumpBytecode=true"

echo "=== Starting application ==="
echo "  Agent: $AGENT_JAR"
echo "  App:   $APP_JAR"
echo "  Debug: localhost:5005 (suspend=y)"
echo ""

exec java \
  -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 \
  -javaagent:"$AGENT_JAR=$AGENT_ARGS" \
  -jar "$APP_JAR"
