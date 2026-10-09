#!/usr/bin/env bash
# A fake `systemctl` for DeployScriptTest. is-active answers for the firewall unit (FAKE_FIREWALL=inactive makes it fail);
# start records the request (the pre-deploy backup) and fails when FAKE_BACKUP_FAIL is set.
echo "systemctl $*" >> "${FAKE_STATE:?}/calls.log"
case "$1" in
  is-active) [ "${FAKE_FIREWALL:-active}" = "active" ] ;;
  start) [ -z "${FAKE_BACKUP_FAIL:-}" ] ;;
  *) echo "fake systemctl: unexpected: $*" >&2; exit 2 ;;
esac
