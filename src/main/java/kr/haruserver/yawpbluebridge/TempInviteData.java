package kr.haruserver.yawpbluebridge;

import java.util.UUID;

public class TempInviteData {
    private String targetUuid;
    private String targetName;
    private String areaId;
    private long startTimeMillis;
    private long durationMinutes;

    public TempInviteData() {}

    public TempInviteData(UUID targetUuid, String targetName, String areaId, long durationMinutes) {
        this.targetUuid = targetUuid.toString();
        this.targetName = targetName;
        this.areaId = areaId;
        this.startTimeMillis = System.currentTimeMillis(); // 초대/갱신 시점 기준 리셋
        this.durationMinutes = durationMinutes;
    }

    // 소유자-대상 간 중복 방지를 위한 고유 키 (예: "ownerUUID_targetUUID")
    public String getCompositeKey() {
        return areaId + "_" + targetUuid;
    }

    public UUID getTargetUuid() { return UUID.fromString(targetUuid); }
    public String getTargetName() { return targetName; }
    public String getAreaId() { return areaId; }
    public long getStartTimeMillis() { return startTimeMillis; }
    public long getDurationMinutes() { return durationMinutes; }

    public boolean isExpired() {
        long expireTime = startTimeMillis + (durationMinutes * 60 * 1000);
        return System.currentTimeMillis() >= expireTime;
    }
}