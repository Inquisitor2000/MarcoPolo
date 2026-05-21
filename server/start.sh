#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR" || exit 1

echo "┌─────────────────────────────────────────────────────────┐"
echo "│  Starting Marco Polo Relay Server                       │"
echo "└─────────────────────────────────────────────────────────┘"
echo ""

node server.js
