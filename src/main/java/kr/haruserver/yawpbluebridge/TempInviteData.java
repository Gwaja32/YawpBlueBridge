package kr.haruserver.yawpbluebridge;

import java.util.UUID;

public class TempInviteData {
    private String targetUuid;
    private String targetName;
    private String ownerUuid;
    private long startTimeMillis;
    private long durationMinutes;

    public TempInviteData() {}

    public TempInviteData(UUID targetUuid, String targetName, UUID ownerUuid, long durationMinutes) {
        this.targetUuid = targetUuid.toString();
        this.targetName = targetName;
        this.ownerUuid = ownerUuid.toString();
        this.startTimeMillis = System.currentTimeMillis(); // 초대/갱신 시점 기준 리셋
        this.durationMinutes = durationMinutes;
    }

    // 소유자-대상 간 중복 방지를 위한 고유 키 (예: "ownerUUID_targetUUID")
    public String getCompositeKey() {
        return ownerUuid + "_" + targetUuid;
    }

    public UUID getTargetUuid() { return UUID.fromString(targetUuid); }
    public String getTargetName() { return targetName; }
    public UUID getOwnerUuid() { return UUID.fromString(ownerUuid); }
    public long getStartTimeMillis() { return startTimeMillis; }
    public long getDurationMinutes() { return durationMinutes; }

    public boolean isExpired() {
        long expireTime = startTimeMillis + (durationMinutes * 60 * 1000);
        return System.currentTimeMillis() >= expireTime;
    }
}