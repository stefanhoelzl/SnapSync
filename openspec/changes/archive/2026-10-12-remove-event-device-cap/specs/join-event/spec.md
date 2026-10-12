# Spec Delta

## REMOVED Requirements

### Requirement: A full event is reported as full
**Reason**: An event can no longer be full (capability `event-lifetime`: there is no device limit), so the join
screen has nothing to report.
**Migration**: None. A guest who could not join a full event before can now join it; installed versions that
still carry the full screen never reach it.
