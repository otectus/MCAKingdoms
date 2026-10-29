#!/usr/bin/env python3
"""One vocabulary for every check script's result (H04): PASS, FAIL, SKIPPED (a prerequisite this machine does not have)
and BLOCKED (waiting on another project). A skipped or blocked check is never reported as passed. With
MCAKINGDOMS_RELEASE=1 a skip or block fails instead, so a release run cannot be green without its external suites."""
import os, sys

EXIT = {'PASS': 0, 'FAIL': 1, 'SKIPPED': 3, 'BLOCKED': 4}


def release():
    return os.environ.get('MCAKINGDOMS_RELEASE', '').lower() in ('1', 'true', 'yes')


def finish(status, reason, name=None):
    """Prints one result line and exits with its code; in release mode SKIPPED and BLOCKED become FAIL."""
    label = name or os.path.basename(sys.argv[0])
    if status in ('SKIPPED', 'BLOCKED') and release():
        print(f'FAIL: {label}: {status.lower()} in release mode: {reason}', flush=True)
        raise SystemExit(EXIT['FAIL'])
    print(f'{status}: {label}: {reason}', flush=True)
    raise SystemExit(EXIT[status])
