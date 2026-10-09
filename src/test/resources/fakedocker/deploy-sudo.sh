#!/usr/bin/env bash
# A fake `sudo` for DeployScriptTest: just runs the command (which is the fake systemctl).
exec "$@"
