# 🐕‍🦺RDP-Watchdog

A background Java tool built for RDP servers. It monitors open sessions, tracks user idle time, and automatically closes **RM.exe**(the program that I chose, can be changed if wanted) when a session has been inactive for too long, not needing any manual intervention.

---

### 📎How it works

The program runs a continuous loop, checking every **10 seconds** for active RDP sessions with the chosen program open. It uses Windows' built-in 'quser' and 'tasklist' commands to gather session data, then decides whether to act based on how long a user has been idle.

Once idle time hits a defined amount of time, it first attempts a graceful close. If RM doesn't respond, it forces the kill. Every action is timestamped and written to a log file (rm-manager.log).

There's also a 10-minute grace period on startup so the program doesn't immediately act on sessions that were already open when it launched, to avoid any problems.

---

### 🍗Features

- **Session detection** — identifies which RDP sessions have RM.exe running, by session ID and username
- **Idle tracking** — reads and compares `quser` idle values each cycle to detect inactivity(tried other ways but had some bugs)
- **Configurable thresholds** — idle timeout, check interval and grace period are all defined at the top of the file
- **Multi-session support** — tracks multiple sessions simultaneously, each with their own idle timer
- **Soft/forced close** — tries a soft close first, escalates to force kill if needed
- **User exclusions** — specific users can be whitelisted and skipped entirely
- **Logging** — all events are logged to `rm-manager.log` with timestamps

---

No external dependencies — runs on plain Java using Windows system commands.