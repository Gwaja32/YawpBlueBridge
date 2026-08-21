package kr.haruserver.yawpbluebridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.gson.MarkerGson;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import com.flowpowered.math.vector.Vector2d;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import de.z0rdak.yawp.api.core.RegionManager;
import de.z0rdak.yawp.core.area.CuboidArea;
import de.z0rdak.yawp.core.flag.BooleanFlag;
import de.z0rdak.yawp.core.flag.FlagState;
import de.z0rdak.yawp.core.flag.RegionFlag;
import de.z0rdak.yawp.core.group.PlayerContainer;
import de.z0rdak.yawp.core.region.CuboidRegion;
import de.z0rdak.yawp.core.region.IMarkableRegion;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public class YawpBlueBridge implements ModInitializer {
    private HttpServer httpServer;
    private ExecutorService apiExecutor;
    private static final int API_PORT = 8103;
    private final Map<String, MarkerSet> worldMarkerSets = new ConcurrentHashMap<>();
    private static final String MARKER_SET_ID = "yawp_markers";
    private static final String MARKER_SET_LABEL = "구역 마커";
    private static final Gson GSON = new Gson();
    // 영구 저장소 경로 (config/yawp-bridge/markers)
    private final Path yawpDataDir = FabricLoader.getInstance().getConfigDir().resolve("yawp-bridge/markers");
    // 블루맵 웹 라이브 경로
    private final Path blueMapWebDir = FabricLoader.getInstance().getGameDir().resolve("bluemap/web/maps");
    private MinecraftServer minecraftServer;
    private BlueMapAPI blueMapApi;

    public void setBlueMapApi(BlueMapAPI api) {
        this.blueMapApi = api;
    }

    @Override
    public void onInitialize() {
        // 1. 블루맵 연결 설정
        BlueMapAPI.onEnable(api -> {
            this.setBlueMapApi(api);
            restoreMarkersFromYawpStorage(); // 여기서 저장해둔 마커를 API로 쏴줌
            System.out.println("[YawpBlueBridge] BlueMap API Connected.");
        });

        // 2. 서버 시작 시 HTTP 서버 실행
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            this.minecraftServer = server;
            startHttpServer();
        });

        // ✅ 3. 서버 종료 시 HTTP 서버 및 스레드 풀 정지 (이걸 추가하세요!)
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            System.out.println("[YawpBlueBridge] Server stopping... Cleaning up API resources.");

            if (this.httpServer != null) {
                // 0초 대기 후 즉시 중단
                this.httpServer.stop(0);
                System.out.println("[YawpBlueBridge] HTTP Server stopped.");
            }

            if (this.apiExecutor != null) {
                // 실행 중인 모든 API 스레드 강제 종료
                this.apiExecutor.shutdownNow();
                System.out.println("[YawpBlueBridge] API Thread Pool closed.");
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> AuthCommand.register(dispatcher));
    }

    private void startHttpServer() {
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress(API_PORT), 0);

            this.httpServer.createContext("/api/yawp/", exchange -> {
                setCorsHeaders(exchange);

                // 🚨 브라우저의 사전 확인(OPTIONS) 요청에 무조건 204나 200으로 응답
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }

                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();

                try {
                    if (path.endsWith("update-marker") && "POST".equalsIgnoreCase(method)) handleUpdateMarker(exchange);
                    else if (path.endsWith("delete-marker") && "DELETE".equalsIgnoreCase(method)) handleDeleteMarker(exchange);
                    else if (path.endsWith("add-member") && "POST".equalsIgnoreCase(method)) handleAddMember(exchange);
                    else if (path.endsWith("remove-member") && "POST".equalsIgnoreCase(method)) handleRemoveMember(exchange);
                    else if (path.endsWith("get-area-info") && "GET".equalsIgnoreCase(method)) handleGetAreaInfo(exchange);
                    else if (path.endsWith("update-area") && "POST".equalsIgnoreCase(method)) handleUpdateArea(exchange);
                    else exchange.sendResponseHeaders(405, -1);
                } catch (Exception e) {
                    e.printStackTrace();
                    sendResponse(exchange, "{\"error\":\"" + e.getMessage() + "\"}", 500);
                }
            });

            // 1. 스레드 풀을 변수에 담아 관리
            this.apiExecutor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true); // 반드시 데몬 스레드여야 함
                return t;
            });

            this.httpServer.setExecutor(this.apiExecutor);
            this.httpServer.start();
            System.out.println("[YawpBlueBridge] API Server started on port " + API_PORT);
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void handleUpdateMarker(HttpExchange exchange) throws IOException, ExecutionException, InterruptedException, TimeoutException {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            JsonObject data = GSON.fromJson(reader, JsonObject.class);
            String id = data.get("id").getAsString();
            String label = data.get("label").getAsString();
            String playerName = data.has("currentNickname") ? data.get("currentNickname").getAsString() : "Unknown";
            String worldStr = data.has("world") ? data.get("world").getAsString() : "minecraft:overworld";

            // 1. 좌표 정밀 계산 (Double.MIN_VALUE 버그 수정 버전)
            List<Vector2d> bmPoints = new ArrayList<>();
            double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

            for (var p : data.getAsJsonArray("points")) {
                JsonObject pt = p.getAsJsonObject();
                double x = pt.get("x").getAsDouble();
                double z = pt.get("z").getAsDouble();
                bmPoints.add(new Vector2d(x, z));
                minX = Math.min(minX, x); minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x); maxZ = Math.max(maxZ, z);
            }

            final String effectiveWorld = switch (worldStr.toLowerCase()) {
                case "world" -> "minecraft:overworld";
                case "world_the_nether" -> "minecraft:the_nether";
                case "world_the_end" -> "minecraft:the_end";
                default -> worldStr; // 그 외의 경우(예: 커스텀 월드)는 입력값 그대로 사용
            };

            // handleUpdateMarker 내부의 마커 생성 전 단계에 추가
            if (isOverlapping(bmPoints, effectiveWorld)) {
                sendResponse(exchange, "{\"error\":\"Already occupied area!\", \"code\":\"OVERLAP\"}", 400);
                return; // 생성을 중단하고 즉시 리턴
            }

            CompletableFuture<Boolean> mainThreadFuture = new CompletableFuture<>();

            // 2. YAWP 구역 생성 (메인 스레드)
            double fMinX = minX; double fMinZ = minZ; double fMaxX = maxX; double fMaxZ = maxZ;
            this.minecraftServer.execute(() -> {
                try {
                    var apiOpt = RegionManager.get().getDimRegionApiByKey(effectiveWorld);
                    if (apiOpt.isPresent()) {
                        BlockPos p1 = new BlockPos((int)Math.floor(fMinX), -64, (int)Math.floor(fMinZ));
                        BlockPos p2 = new BlockPos((int)Math.ceil(fMaxX), 320, (int)Math.ceil(fMaxZ));

                        Constructor<CuboidArea> areaConst = CuboidArea.class.getDeclaredConstructor(BlockPos.class, BlockPos.class);
                        areaConst.setAccessible(true);
                        CuboidArea yawpArea = areaConst.newInstance(p1, p2);

                        ResourceKey<@NotNull Level> worldKey = ResourceKey.create(Level.OVERWORLD.registryKey(), Identifier.parse(effectiveWorld));
                        Constructor<CuboidRegion> regionConst = CuboidRegion.class.getDeclaredConstructor(
                                String.class, CuboidArea.class, net.minecraft.world.entity.player.Player.class, ResourceKey.class
                        );
                        regionConst.setAccessible(true);
                        CuboidRegion region = regionConst.newInstance(id, yawpArea, null, worldKey);

                        apiOpt.get().addLocalRegion(region);
                        RegionManager.get().saveAll();
                        setupPlayerPermissions(region, playerName);
                        mainThreadFuture.complete(true);
                    } else { mainThreadFuture.complete(false); }
                } catch (Exception e) { e.printStackTrace(); mainThreadFuture.complete(false); }
            });

            // 3. 블루맵 업데이트 (성공 시)
            if (mainThreadFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)) {
                syncAllBlueMapLayers(id, label, bmPoints, effectiveWorld);
                sendResponse(exchange, "{\"success\":true}", 200);
            } else {
                sendResponse(exchange, "{\"error\":\"World Not Found\"}", 404);
            }
        }
    }

    private boolean isOverlapping(List<Vector2d> newPoints, String worldStr) {
        if (this.blueMapApi == null || newPoints == null || newPoints.isEmpty()) return false;

        // 1. 새 영역의 최소/최대 좌표 계산 (Bounding Box)
        double newMinX = newPoints.stream().mapToDouble(Vector2d::getX).min().orElse(0);
        double newMaxX = newPoints.stream().mapToDouble(Vector2d::getX).max().orElse(0);
        double newMinY = newPoints.stream().mapToDouble(Vector2d::getY).min().orElse(0);
        double newMaxY = newPoints.stream().mapToDouble(Vector2d::getY).max().orElse(0);

        // 2. 입력받은 worldStr과 정확히 일치하는 월드 찾기
        // BlueMap API에서 해당 월드 객체를 직접 가져옵니다.
        return blueMapApi.getWorld(worldStr).map(world -> {
            // 해당 월드에 속한 모든 맵을 순회
            for (BlueMapMap map : world.getMaps()) {
                MarkerSet ms = map.getMarkerSets().get(MARKER_SET_ID);
                if (ms == null) continue;

                for (Marker marker : ms.getMarkers().values()) {
                    if (!(marker instanceof ExtrudeMarker existing)) continue;

                    // 기존 마커의 Shape 좌표들 추출
                    Vector2d[] existingPoints = existing.getShape().getPoints();
                    double exMinX = Double.MAX_VALUE, exMaxX = -Double.MAX_VALUE;
                    double exMinY = Double.MAX_VALUE, exMaxY = -Double.MAX_VALUE;

                    for (Vector2d p : existingPoints) {
                        exMinX = Math.min(exMinX, p.getX()); exMaxX = Math.max(exMaxX, p.getX());
                        exMinY = Math.min(exMinY, p.getY()); exMaxY = Math.max(exMaxY, p.getY());
                    }

                    // 3. AABB(Axis-Aligned Bounding Box) 충돌 검사
                    // 동일 월드 내의 마커와 영역이 겹치는지 확인
                    if (newMinX < exMaxX && newMaxX > exMinX && newMinY < exMaxY && newMaxY > exMinY) {
                        return true; // 겹침 발견
                    }
                }
            }
            return false; // 해당 월드의 어떤 맵에서도 겹치지 않음
        }).orElse(false); // 월드를 찾을 수 없는 경우
    }

    private void syncAllBlueMapLayers(String id, String label, List<Vector2d> points, String worldStr) {
        if (this.blueMapApi == null) return;

        Vector2d midPoint = findMidpoint(points);
        com.flowpowered.math.vector.Vector3d pos = new com.flowpowered.math.vector.Vector3d(midPoint.getX(), 64.0, midPoint.getY());

        ExtrudeMarker bmMarker = ExtrudeMarker.builder()
                .label(label)
                .position(pos)
                .shape(new Shape(points.toArray(new Vector2d[0])), -64f, 320f)
                .lineColor(new Color(255, 0, 0, 1.0f))
                .fillColor(new Color(255, 0, 0, 0.4f))
                .build();
        bmMarker.setMaxDistance(5000);

        String targetId = worldStr.replace("minecraft:", "").toLowerCase();

        this.blueMapApi.getMaps().stream()
                .filter(map -> map.getWorld().getId().toLowerCase().contains(targetId))
                .forEach(map -> {
                    MarkerSet ms = map.getMarkerSets().computeIfAbsent(MARKER_SET_ID, k ->
                            MarkerSet.builder().label(MARKER_SET_LABEL).build()
                    );

                    // 1. 메모리 데이터 갱신
                    ms.getMarkers().put(id, bmMarker);
                    map.getMarkerSets().put(MARKER_SET_ID, ms);

                    // 2. [강제 동기화 핵심]
                    // 블루맵에게 이 맵의 상태가 바뀌었으니 즉시 처리하라고 명령합니다.
                    forceBlueMapSync(map);

                    // 3. 백업 저장
                    CompletableFuture.runAsync(() -> saveToYawpStorage(map, ms), apiExecutor);
                });
    }

    // BlueBridge에서 가져온 정교한 중심점 계산 로직
    private Vector2d findMidpoint(List<Vector2d> polygon) {
        if (polygon.isEmpty()) return new Vector2d(0, 0);
        double minX = polygon.getFirst().getX(), maxX = minX;
        double minY = polygon.getFirst().getY(), maxY = minY;
        for (Vector2d v : polygon) {
            minX = Math.min(minX, v.getX()); maxX = Math.max(maxX, v.getX());
            minY = Math.min(minY, v.getY()); maxY = Math.max(maxY, v.getY());
        }
        return new Vector2d(minX + (maxX - minX) / 2.0, minY + (maxY - minY) / 2.0);
    }

    private void saveToYawpStorage(BlueMapMap map, MarkerSet ms) {
        try {
            if (!Files.exists(yawpDataDir)) Files.createDirectories(yawpDataDir);
            File file = yawpDataDir.resolve(map.getId() + ".json").toFile();
            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                MarkerGson.INSTANCE.toJson(ms, writer);
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void restoreMarkersFromYawpStorage() {
        if (this.blueMapApi == null || !Files.exists(yawpDataDir)) return;

        for (var map : this.blueMapApi.getMaps()) {
            File file = yawpDataDir.resolve(map.getId() + ".json").toFile();
            if (!file.exists()) continue;

            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                MarkerSet loadedSet = MarkerGson.INSTANCE.fromJson(reader, MarkerSet.class);
                if (loadedSet != null) {
                    // [수정] API 메모리에만 넣어주면 블루맵이 나머지는 알아서 처리합니다.
                    map.getMarkerSets().put(MARKER_SET_ID, loadedSet);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void saveMarkersToFile() {
        if (this.blueMapApi == null) return;

        try {
            // 1. 우리만의 영구 저장소 폴더가 없으면 생성
            if (!Files.exists(yawpDataDir)) {
                Files.createDirectories(yawpDataDir);
            }

            for (var map : this.blueMapApi.getMaps()) {
                MarkerSet ms = map.getMarkerSets().get(MARKER_SET_ID);

                // 파일 경로 설정 (예: config/yawp-bridge/markers/world.json)
                File file = yawpDataDir.resolve(map.getId() + ".json").toFile();

                // 마커가 없으면 기존 파일을 삭제하거나 빈 상태로 저장
                if (ms == null || ms.getMarkers().isEmpty()) {
                    if (file.exists()) file.delete();
                    continue;
                }

                try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                    // [핵심] MarkerGson을 사용하여 블루맵 표준 객체 구조로 저장
                    // 이렇게 저장해야 나중에 restoreMarkersFromYawpStorage()에서 완벽히 복구됩니다.
                    MarkerGson.INSTANCE.toJson(ms, writer);
                    writer.flush();
                } catch (IOException e) {
                    System.err.println("[YawpDebug] 파일 쓰기 오류 (" + map.getId() + "): " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[YawpDebug] 저장 폴더 생성 실패: " + e.getMessage());
        }
    }

    // 헬퍼 메서드: 플레이어 권한 설정 전용
    private void setupPlayerPermissions(IMarkableRegion region, String playerName) {
        if (playerName == null || playerName.equals("Unknown")) return;

        var cache = this.minecraftServer.services().nameToIdCache();
        var profileOpt = cache.get(playerName);

        profileOpt.ifPresent(profile -> {
            try {
                // [수정] UnmodifiableMap 에러를 피하기 위해 get을 먼저 시도
                var groups = region.getGroups();

                // 1. Members 그룹 처리
                PlayerContainer members = groups.get("members");
                if (members == null) {
                    // 만약 맵이 수정 불가능하다면 이 부분에서 에러가 날 수 있으므로
                    // YAWP API 사양에 따라 새로운 Container를 put 할 수 있는지 확인
                    members = new PlayerContainer("members");
                    try {
                        region.getGroups().put("members", members);
                    } catch (UnsupportedOperationException e) {
                        return;
                    }
                }
                members.addPlayer(profile.id(), profile.name());

                // 2. Owners 그룹 처리
                PlayerContainer owners = groups.get("owners");
                if (owners == null) {
                    owners = new PlayerContainer("owners");
                    region.getGroups().put("owners", owners);
                }
                owners.addPlayer(profile.id(), profile.name());

                // 변경사항 저장
                RegionManager.get().saveAll();

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void writeToBlueMapLiveFile(BlueMapMap map, MarkerSet ms) {
        Path livePath = blueMapWebDir.resolve(map.getId()).resolve("live/markers.json");

        try {
            JsonObject root;
            // 1. 기존 파일 읽기 (다른 마커셋 보존용)
            if (Files.exists(livePath)) {
                try (BufferedReader reader = Files.newBufferedReader(livePath, StandardCharsets.UTF_8)) {
                    root = GSON.fromJson(reader, JsonObject.class);
                } catch (Exception e) {
                    root = new JsonObject();
                }
            } else {
                root = new JsonObject();
                if (!Files.exists(livePath.getParent())) Files.createDirectories(livePath.getParent());
            }

            // 2. markerSets 레이어 확보
            if (!root.has("markerSets") || !root.get("markerSets").isJsonObject()) {
                root.add("markerSets", new JsonObject());
            }
            JsonObject markerSets = root.getAsJsonObject("markerSets");

            // 3. [삭제/업데이트 핵심]
            // ms에 마커가 남아있으면 갱신, 완전히 비어있으면 레이어 자체를 삭제
            if (ms == null || ms.getMarkers().isEmpty()) {
                markerSets.remove(MARKER_SET_ID);
            } else {
                markerSets.add(MARKER_SET_ID, MarkerGson.INSTANCE.toJsonTree(ms));
            }

            // 4. 불필요한 상위 레벨 쓰레기 데이터 청소 (중복 방지)
            root.remove(MARKER_SET_ID);

            // 5. 원자적 파일 쓰기 및 타임스탬프 갱신
            try (BufferedWriter writer = Files.newBufferedWriter(livePath, StandardCharsets.UTF_8)) {
                new Gson().toJson(root, writer);
            }
            Files.setLastModifiedTime(livePath, FileTime.from(Instant.now()));

        } catch (IOException e) {
            System.err.println("[YawpError] 라이브 파일 실시간 갱신 실패: " + e.getMessage());
        }
    }

    private void forceBlueMapSync(BlueMapMap map) {
        if (this.blueMapApi == null) return;

        // 1. [방법 1] 렌더 매니저를 통한 업데이트 태스크 스케줄링
        // 이 메서드는 맵의 데이터를 다시 읽고 웹 서버에 갱신 신호를 보냅니다.
        this.blueMapApi.getRenderManager().scheduleMapUpdateTask(map);

        // 2. [방법 2] 웹앱(WebApp)의 리소스 캐시를 강제로 무효화 시도
        // 웹 클라이언트가 'Etag'나 'Last-Modified'를 확인하도록 유도합니다.
        try {
            Path livePath = blueMapWebDir.resolve(map.getId()).resolve("live/markers.json");
            if (Files.exists(livePath)) {
                // 파일을 건드리는 게 아니라, 블루맵이 이 파일을 '새것'으로 인식하게끔
                // 파일 시스템 레벨에서 접근 신호를 보냅니다.
                Files.setLastModifiedTime(livePath, FileTime.from(Instant.now().plusSeconds(1)));
            }
        } catch (IOException e) {
            // 무시
        }
    }

    private void handleDeleteMarker(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String id = (query != null && query.contains("id=")) ? query.split("id=")[1].split("&")[0] : null;

        if (id == null) {
            sendResponse(exchange, "{\"error\":\"ID missing\"}", 400);
            return;
        }

        boolean bluemapRemoved = false;
        if (this.blueMapApi != null) {
            for (var map : this.blueMapApi.getMaps()) {
                MarkerSet oldMs = map.getMarkerSets().get(MARKER_SET_ID);
                if (oldMs != null && oldMs.getMarkers().containsKey(id)) {

                    // [변경 핵심] 새로운 마커셋 빌드 (객체 참조 변경)
                    MarkerSet newMs = MarkerSet.builder()
                            .label(MARKER_SET_LABEL)
                            .build();

                    // 삭제할 ID만 제외하고 복사
                    oldMs.getMarkers().forEach((mId, m) -> {
                        if (!mId.equals(id)) newMs.getMarkers().put(mId, m);
                    });

                    // 2. 메모리 갱신
                    map.getMarkerSets().put(MARKER_SET_ID, newMs);

                    // 3. 🔥 강제 동기화 트리거 (삭제 신호를 웹으로 즉시 전송)
                    forceBlueMapSync(map);

                    // 4. 파일 및 저장소 반영
                    CompletableFuture.runAsync(() -> {
                        saveToYawpStorage(map, newMs);
                        writeToBlueMapLiveFile(map, newMs);
                    }, apiExecutor);

                    bluemapRemoved = true;
                }
            }
        }

        // YAWP 데이터 삭제
        boolean yawpRemoved = false;
        for (var worldKey : this.minecraftServer.levelKeys()) {
            String worldName = worldKey.identifier().toString();
            var apiOpt = RegionManager.get().getDimRegionApiByKey(worldName);
            if (apiOpt.isPresent()) {
                var api = apiOpt.get();
                var regionOpt = api.getLocalRegion(id);
                if (regionOpt.isPresent()) {
                    api.removeLocal(regionOpt.get());
                    RegionManager.get().saveAll();
                    yawpRemoved = true;
                    break;
                }
            }
        }

        // 결과 응답
        if (bluemapRemoved || yawpRemoved) {
            sendResponse(exchange, "{\"success\":true}", 200);
        } else {
            sendResponse(exchange, "{\"error\":\"Not Found\"}", 404);
        }
    }

    private void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private void sendResponse(HttpExchange exchange, String response, int code) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void handleAddMember(HttpExchange exchange) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            JsonObject data = GSON.fromJson(reader, JsonObject.class);
            String regionId = data.get("id").getAsString();
            String playerName = data.get("playerName").getAsString();

            if (this.minecraftServer == null) {
                sendResponse(exchange, "{\"error\":\"Server not ready\"}", 503);
                return;
            }

            // 🚨 직접 찾으신 경로 적용: ApiServices를 통해 UserCache 획득
            // NameToIdCache 인터페이스로 받아도 findByName 사용이 가능합니다.
            var cache = this.minecraftServer.services().nameToIdCache();
            var profileOpt = cache.get(playerName);

            if (profileOpt.isEmpty()) {
                sendResponse(exchange, "{\"error\":\"플레이어 기록을 찾을 수 없습니다.\"}", 404);
                return;
            }

            var profile = profileOpt.get();
            java.util.UUID uuid = profile.id();
            String name = profile.name();

            // 모든 월드 순회하며 해당 지역 찾기
            boolean found = false;
            for (String dimId : this.minecraftServer.levelKeys().stream()
                    .map(key -> key.identifier().toString()).toList()) {

                var apiOpt = RegionManager.get().getDimRegionApiByKey(dimId);
                if (apiOpt.isPresent() && apiOpt.get().hasLocal(regionId)) {
                    IMarkableRegion region = apiOpt.get().getLocalRegion(regionId).orElseThrow();

                    PlayerContainer memberGroup = region.getGroups().get("members");
                    if (memberGroup == null) {
                        memberGroup = new PlayerContainer("members");
                        region.getGroups().put("members", memberGroup);
                    }

                    memberGroup.addPlayer(uuid, name);
                    RegionManager.get().saveAll();
                    found = true;
                    break;
                }
            }

            if (found) {
                sendResponse(exchange, "{\"success\":true}", 200);
            } else {
                sendResponse(exchange, "{\"error\":\"해당 ID의 지역을 찾을 수 없습니다.\"}", 404);
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, "{\"error\":\"서버 오류: " + e.getMessage() + "\"}", 500);
        }
    }

    private void handleRemoveMember(HttpExchange exchange) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            JsonObject data = GSON.fromJson(reader, JsonObject.class);
            String regionId = data.has("regionId") ? data.get("regionId").getAsString() : data.get("id").getAsString();
            String playerName = data.get("playerName").getAsString();

            // 🚨 이전에 성공한 UserCache 경로 활용
            var cache = this.minecraftServer.services().nameToIdCache();
            var profileOpt = cache.get(playerName);

            if (profileOpt.isEmpty()) {
                sendResponse(exchange, "{\"error\":\"플레이어 기록 없음\"}", 404);
                return;
            }

            UUID uuid = profileOpt.get().id();
            boolean removed = false;

            // 모든 월드 순회하며 삭제 시도
            for (String dimId : this.minecraftServer.levelKeys().stream()
                    .map(key -> key.identifier().toString()).toList()) {

                var apiOpt = RegionManager.get().getDimRegionApiByKey(dimId);
                if (apiOpt.isPresent() && apiOpt.get().hasLocal(regionId)) {
                    IMarkableRegion region = apiOpt.get().getLocalRegion(regionId).orElseThrow();
                    PlayerContainer members = region.getGroups().get("members");

                    if (members != null) {
                        // 🚨 멤버 삭제 실행
                        members.removePlayer(uuid);
                        RegionManager.get().saveAll();
                        removed = true;
                        break;
                    }
                }
            }

            if (removed) sendResponse(exchange, "{\"success\":true}", 200);
            else sendResponse(exchange, "{\"error\":\"멤버를 찾을 수 없음\"}", 404);
        }
    }

    private void handleGetAreaInfo(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();

        if (query == null || !query.contains("id=")) {
            sendResponse(exchange, "{\"error\":\"Missing id parameter\"}", 400);
            return;
        }
        String regionId = query.split("id=")[1].split("&")[0];

        JsonObject response = new JsonObject();
        JsonArray membersArray = new JsonArray();
        boolean found = false;

        for (var worldKey : this.minecraftServer.levelKeys()) {
            String dimId = worldKey.identifier().toString();
            var apiOpt = RegionManager.get().getDimRegionApiByKey(dimId);

            if (apiOpt.isPresent() && apiOpt.get().hasLocal(regionId)) {
                IMarkableRegion region = apiOpt.get().getLocalRegion(regionId).orElseThrow();

                // --- [수정 시작] BlueMap API에서 직접 마커 정보 추출 ---
                boolean markerFound = false;
                if (this.blueMapApi != null) {
                    // 모든 지도를 순회하며 해당 마커 ID를 찾습니다.
                    for (var map : this.blueMapApi.getMaps()) {
                        var ms = map.getMarkerSets().get(MARKER_SET_ID);
                        if (ms != null && ms.getMarkers().containsKey(regionId)) {
                            var marker = ms.getMarkers().get(regionId);

                            response.addProperty("label", marker.getLabel());

                            if (marker instanceof ExtrudeMarker extrudeMarker) {
                                response.addProperty("detail", extrudeMarker.getDetail());

                                Color c = extrudeMarker.getLineColor();
                                if (c != null) {
                                    JsonObject colorObj = new JsonObject();
                                    colorObj.addProperty("r", c.getRed());
                                    colorObj.addProperty("g", c.getGreen());
                                    colorObj.addProperty("b", c.getBlue());
                                    response.add("color", colorObj);
                                }
                            }
                            markerFound = true;
                            break; // 마커를 찾았으면 지도 순회 중단
                        }
                    }
                }

                if (!markerFound) {
                    response.addProperty("label", "Unknown Region");
                    response.addProperty("detail", "");
                }
                // --- [수정 종료] ---

                // 2. 플래그 처리
                JsonArray flagsArray = new JsonArray();
                region.getFlags().getFlagMap().forEach((k, v) -> {
                    if (v.doesOverride()) {
                        JsonObject flagDetail = new JsonObject();
                        flagDetail.addProperty("id", k);
                        flagDetail.addProperty("allowed", (v.getState() == FlagState.ALLOWED));
                        flagDetail.addProperty("enabled", true);
                        flagsArray.add(flagDetail);
                    }
                });
                response.add("flags", flagsArray);

                // 4. 멤버 처리
                PlayerContainer members = region.getGroups().get("members");
                if (members != null && members.getPlayers() != null) {
                    members.getPlayers().values().forEach(membersArray::add);
                }

                found = true;
                break;
            }
        }

        if (!found) {
            sendResponse(exchange, "{\"error\":\"Region not found\"}", 404);
            return;
        }

        response.add("members", membersArray);
        sendResponse(exchange, GSON.toJson(response), 200);
    }

    private void handleUpdateArea(HttpExchange exchange) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            JsonObject data = GSON.fromJson(reader, JsonObject.class);

            String regionId = data.get("id").getAsString();
            String newLabel = data.get("label").getAsString();
            String newDetail = data.has("detail") ? data.get("detail").getAsString() : "";
            JsonObject colorData = data.getAsJsonObject("color");

            JsonElement flagsElement = data.get("flags");
            JsonArray flagsArray = (flagsElement != null && flagsElement.isJsonArray()) ? flagsElement.getAsJsonArray() : null;

            boolean found = false;

            // 1. 모든 월드를 순회하며 YAWP 구역 찾기
            for (var worldKey : this.minecraftServer.levelKeys()) {
                String worldName = worldKey.identifier().toString();
                var apiOpt = RegionManager.get().getDimRegionApiByKey(worldName);

                if (apiOpt.isPresent() && apiOpt.get().hasLocal(regionId)) {
                    IMarkableRegion region = apiOpt.get().getLocalRegion(regionId).orElseThrow();

                    // --- [YAWP 플래그 수정 로직] ---
                    List<String> flagsToRemove = new ArrayList<>(region.getFlags().getFlagMap().keySet());
                    for (String flagName : flagsToRemove) {
                        region.removeFlag(flagName);
                    }

                    if (flagsArray != null) {
                        for (JsonElement element : flagsArray) {
                            JsonObject fObj = element.getAsJsonObject();
                            String flagId = fObj.get("id").getAsString();
                            if (fObj.get("enabled").getAsBoolean()) {
                                try {
                                    RegionFlag rf = RegionFlag.fromId(flagId);
                                    FlagState state = fObj.get("allowed").getAsBoolean() ? FlagState.ALLOWED : FlagState.DENIED;
                                    region.getFlags().put(new BooleanFlag(rf, state, true));
                                } catch (IllegalArgumentException e) {
                                    System.out.println("[Yawp] 알 수 없는 플래그 무시됨: " + flagId);
                                }
                            }
                        }
                    }

                    // --- [수정 핵심: BlueMap 마커 직접 수정] ---
                    if (this.blueMapApi != null) {
                        for (var map : this.blueMapApi.getMaps()) {
                            MarkerSet oldMs = map.getMarkerSets().get(MARKER_SET_ID);
                            if (oldMs != null && oldMs.getMarkers().containsKey(regionId)) {

                                // 1. 새로운 마커셋 객체 생성 (참조 변경 트리거)
                                MarkerSet newMs = MarkerSet.builder()
                                        .label(MARKER_SET_LABEL)
                                        .build();
                                newMs.getMarkers().putAll(oldMs.getMarkers());

                                // 2. 기존 마커를 기반으로 '수정된 새 마커' 생성
                                var oldMarker = newMs.getMarkers().get(regionId);
                                if (oldMarker instanceof ExtrudeMarker extrude) {
                                    ExtrudeMarker updatedMarker = ExtrudeMarker.builder()
                                            .label(newLabel)
                                            .detail(newDetail)
                                            .shape(extrude.getShape(), extrude.getShapeMinY(), extrude.getShapeMaxY())
                                            .position(extrude.getPosition())
                                            .maxDistance(extrude.getMaxDistance())
                                            .minDistance(extrude.getMinDistance())
                                            .depthTestEnabled(extrude.isDepthTestEnabled())
                                            .build();

                                    if (colorData != null) {
                                        int r = colorData.get("r").getAsInt();
                                        int g = colorData.get("g").getAsInt();
                                        int b = colorData.get("b").getAsInt();
                                        updatedMarker.setLineColor(new Color(r, g, b, 1.0f));
                                        updatedMarker.setFillColor(new Color(r, g, b, 0.4f));
                                    } else {
                                        updatedMarker.setLineColor(extrude.getLineColor());
                                        updatedMarker.setFillColor(extrude.getFillColor());
                                    }

                                    // 3. 새 마커셋에 수정된 마커 주입
                                    newMs.getMarkers().put(regionId, updatedMarker);

                                    // 4. [중요] 맵에 주입하여 엔진 깨우기
                                    map.getMarkerSets().put(MARKER_SET_ID, newMs);

                                    // 5. 🔥 [추가] 블루맵 엔진 강제 동기화 (RenderManager 호출)
                                    // 웹 소켓을 통해 변경된 색상/라벨 정보를 브라우저에 즉시 전파합니다.
                                    forceBlueMapSync(map);

                                    // 6. [중요] 물리적 파일 병합 및 쓰기 (비동기)
                                    CompletableFuture.runAsync(() -> writeToBlueMapLiveFile(map, newMs), apiExecutor);
                                }
                            }
                        }
                    }

                    RegionManager.get().saveAll();
                    saveMarkersToFile();
                    found = true;
                    break;
                }
            }

            if (found) {
                sendResponse(exchange, "{\"success\":true}", 200);
            } else {
                sendResponse(exchange, "{\"error\":\"Region not found\"}", 404);
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, "{\"error\":\"" + e.getMessage() + "\"}", 500);
        }
    }
}