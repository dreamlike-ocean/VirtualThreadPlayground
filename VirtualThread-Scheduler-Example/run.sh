#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

POLLER_MODE="1"
DEBUG_MODE="false"
if [[ $# -gt 0 ]]; then
  case "$1" in
    1|2|3)
      POLLER_MODE="$1"
      shift
      ;;
    *)
      echo "Usage: $0 [pollerMode: 1|2|3] [debug: true|false] [appArgs...]"
      exit 2
      ;;
  esac
fi

if [[ $# -gt 0 ]]; then
  case "$1" in
    true|false)
      DEBUG_MODE="$1"
      shift
      ;;
  esac
fi

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
case "$POLLER_MODE" in
  1) POLLER_MODE_LABEL="System Poller" ;;
  2) POLLER_MODE_LABEL="Virtualthread Poller" ;;
  3) POLLER_MODE_LABEL="CarrierThreadPoller" ;;
esac
echo "  PollerMode: $POLLER_MODE_LABEL"
if [[ "$DEBUG_MODE" == "true" ]]; then
  echo "  Debug: localhost:5005 (suspend=n)"
else
  echo "  Debug: false"
fi
echo ""

JAVA_DEBUG_ARGS=()
if [[ "$DEBUG_MODE" == "true" ]]; then
  JAVA_DEBUG_ARGS+=("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005")
fi

java -Djdk.pollerMode="$POLLER_MODE" "${JAVA_DEBUG_ARGS[@]}" -javaagent:"$AGENT_JAR=$AGENT_ARGS" -jar "$APP_JAR" "$@"
