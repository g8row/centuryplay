# Testing Requirements and Rules

## Testing Setup
- **Local Machine (Receiver)**
    - IP: `192.168.234.104`
    - Role: Runs `shairport-sync` and `nqptp`
    - **RULE**: NEVER USE SUDO ON THIS MACHINE.
    - **RULE**: DO NOT TOUCH `nqptp` (config or process management).
- **Remote Machine (Sender)**
    - IP: `192.168.234.114`
    - User: `agl`
    - Password: `aglpass` (lowercase)
    - Role: Runs the Python test script `airplay2_transient.py`
    - Connection: Must use SSH Agent variables (`SSH_AUTH_SOCK`, `SSH_AGENT_PID`).

## Execution Command
To run the test, execute from the **Local Machine**:
```bash
echo aglpass | SSH_AUTH_SOCK=... SSH_AGENT_PID=... ssh -T agl@192.168.234.114 "sudo -S -p '' python3 ~/airplay2_transient.py 192.168.234.104"
```
*Note: We pipe the password because `sudo` requires a terminal or -S flag.*

## Workflow Rules
1. **Fix Order**: Fix the Python script (`airplay2_transient.py`) FIRST. Do not touch Kotlin code until Python is verified.
2. **Logs**: Monitor `/tmp/shairport.log` and `/tmp/nqptp.log` on the Local Machine to verify behavior.
3. **Execution**: Always read this file before starting new testing cycles.

## Current Issue (Reproduction)
- **Status**: Reproduction successful. Test executed correctly.
- **Problem**: PTP packet issues. Logs show strange latency behaviors.
- **Heading Diagnosis**: Discrepancy between Wall Clock (`time.time_ns()`) used in PTP Master Clock and Monotonic Clock (`time.monotonic_ns()`) used in Audio Anchors.
