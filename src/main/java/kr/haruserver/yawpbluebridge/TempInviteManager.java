package kr.haruserver.yawpbluebridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import de.z0rdak.yawp.api.core.RegionManager;
import de.z0rdak.yawp.core.group.PlayerContainer;
import de.z0rdak.yawp.core.region.IMarkableRegion;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

public class TempInviteManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE_PATH = Path.of("config", "yawp_temp_invites.json");

    // CompositeKey("ownerUUID_targetUUID") -> TempInviteData 매핑
    private static final Map<String, TempInviteData> invitesMap = new ConcurrentHashMap<>();
    private static ScheduledExecutorService scheduler;

    private static MinecraftServer serverInstance;

    public static void setServer(MinecraftServer server) {
        serverInstance = server;
    }

    public static void init() {
        loadFromFile();

        // 렉 방지를 위해 비동기 백그라운드 스레드 풀 생성 (10초마다 만료 검사)
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(TempInviteManager::checkExpirations, 10, 10, TimeUnit.SECONDS);
    }

    public static void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }

    // 새로운 임시 초대 추가 및 멤버권한 부여
    public static void addInvite(MinecraftServer server, UUID targetUuid, String targetName, UUID ownerUuid, long minutes) {
        long clampedMinutes = Math.max(1, Math.min(300, minutes));

        TempInviteData newData = new TempInviteData(targetUuid, targetName, ownerUuid, clampedMinutes);

        // put()을 사용하므로 동일한 [Owner + Target] 키가 존재하면 기존 데이터를 완전히 대체(startTimeMillis 갱신)함
        invitesMap.put(newData.getCompositeKey(), newData);
        saveToFile();

        // 멤버 권한 보장 (이미 멤버여도 덮어씀)
        applyMemberStatus(server, ownerUuid, targetUuid, targetName, true);
    }

    // 플레이어 접속 시 검사 로직
    public static void onPlayerJoin(UUID targetUuid) {
        boolean changed = false;

        for (Map.Entry<String, TempInviteData> entry : invitesMap.entrySet()) {
            TempInviteData invite = entry.getValue();
            if (invite.getTargetUuid().equals(targetUuid) && invite.isExpired()) {
                applyMemberStatus(serverInstance, invite.getOwnerUuid(), invite.getTargetUuid(), invite.getTargetName(), false);
                invitesMap.remove(entry.getKey());
                changed = true;
            }
        }

        if (changed) {
            saveToFile();
        }
    }

    // 비동기 타이머 검사 (10초 주기)
    private static void checkExpirations() {
        if (invitesMap.isEmpty() || serverInstance == null) return;

        boolean changed = false;

        for (Map.Entry<String, TempInviteData> entry : invitesMap.entrySet()) {
            TempInviteData invite = entry.getValue();

            if (invite.isExpired()) {
                serverInstance.execute(() ->
                        applyMemberStatus(serverInstance, invite.getOwnerUuid(), invite.getTargetUuid(), invite.getTargetName(), false)
                );
                invitesMap.remove(entry.getKey());
                changed = true;
            }
        }

        if (changed) {
            saveToFile();
        }
    }

    // YAWP 영역 멤버 추가 / 제거 공통 로직
    private static void applyMemberStatus(MinecraftServer server, UUID ownerUuid, UUID targetUuid, String targetName, boolean add) {
        List<IMarkableRegion> ownedRegions = getOwnedRegionsFast(ownerUuid);

        for (IMarkableRegion region : ownedRegions) {
            PlayerContainer memberGroup = region.getGroups().get("members");
            if (memberGroup == null) {
                memberGroup = new PlayerContainer("members");
                region.getGroups().put("members", memberGroup);
            }

            if (add) {
                memberGroup.addPlayer(targetUuid, targetName);
            } else {
                memberGroup.removePlayer(targetUuid);
            }
        }
        RegionManager.get().saveAll();
    }

    private static List<IMarkableRegion> getOwnedRegionsFast(UUID playerUuid) {
        List<IMarkableRegion> ownedRegions = new ArrayList<>();

        for (String levelName : RegionManager.get().getLevelNames()) {
            var apiOpt = RegionManager.get().getDimRegionApiByKey(levelName);
            if (apiOpt.isPresent()) {
                var api = apiOpt.get();

                for (IMarkableRegion region : api.getAllLocalRegions()) {
                    PlayerContainer ownerGroup = region.getGroups().get("owners");

                    if (ownerGroup != null && ownerGroup.getPlayers() != null) {
                        if (ownerGroup.getPlayers().containsKey(playerUuid)) {
                            ownedRegions.add(region);
                        }
                    }
                }
            }
        }

        return ownedRegions;
    }

    // 파일 저장/로드
    private static synchronized void saveToFile() {
        try {
            Files.createDirectories(FILE_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE_PATH, StandardCharsets.UTF_8)) {
                // Map 구조를 JSON으로 직렬화하여 저장
                GSON.toJson(invitesMap, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static synchronized void loadFromFile() {
        if (!Files.exists(FILE_PATH)) return;
        try (Reader reader = Files.newBufferedReader(FILE_PATH, StandardCharsets.UTF_8)) {
            Map<String, TempInviteData> loaded = GSON.fromJson(reader, new TypeToken<ConcurrentHashMap<String, TempInviteData>>(){}.getType());
            if (loaded != null) {
                invitesMap.clear();
                invitesMap.putAll(loaded);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
