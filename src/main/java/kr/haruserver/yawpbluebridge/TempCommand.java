package kr.haruserver.yawpbluebridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.z0rdak.yawp.api.FlagRegister;
import de.z0rdak.yawp.api.core.RegionManager;
import de.z0rdak.yawp.core.area.CuboidArea;
import de.z0rdak.yawp.core.flag.BooleanFlag;
import de.z0rdak.yawp.core.flag.FlagState;
import de.z0rdak.yawp.core.flag.RegionFlag;
import de.z0rdak.yawp.core.flag.RegionFlags;
import de.z0rdak.yawp.core.group.PlayerContainer;
import de.z0rdak.yawp.core.region.CuboidRegion;
import de.z0rdak.yawp.core.region.IMarkableRegion;
import de.z0rdak.yawp.data.region.RegionDataManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import javax.net.ssl.*;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class TempCommand {

    public static final List<String> YAWP_FLAG_IDS = List.of(
            "break-blocks",
            "place-blocks",
            "scoop-fluids",
            "place-fluids",
            "ignite-explosives",
            "strip-wood",
            "till-farmland",
            "shovel-path",
            "trample-farmland-player",
            "trample-farmland-other",
            "lightning",
            "leaf-decay",
            "fire-tick",
            "walker-freeze",
            "spawning-all",
            "use-blocks",
            "use-entities",
            "use-items",
            "access-container",
            "enderpearl-to",
            "no-pvp",
            "explosions-blocks",
            "explosions-entities",
            "creeper-explosion-blocks",
            "creeper-explosion-entities",
            "mob-griefing",
            "enderman-griefing",
            "wither-destruction",
            "dragon-destruction",
            "no-sign-edit",
            "snow-melting"
    );

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("구역")
                        .then(Commands.literal("초대")
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 300)) // 최소 1분 이상
                                                .executes(ctx -> {
                                                    CommandSourceStack source = ctx.getSource();
                                                    ServerPlayer sourcePlayer = source.getPlayerOrException();
                                                    String targetName = StringArgumentType.getString(ctx, "target");
                                                    int minutes = IntegerArgumentType.getInteger(ctx, "minutes");

                                                    var cache = source.getServer().services().nameToIdCache();
                                                    var profileOpt = cache.get(targetName);

                                                    if (profileOpt.isEmpty()) {
                                                        source.sendFailure(Component.literal("'" + targetName + "' 님의 플레이어 기록을 찾을 수 없습니다."));
                                                        return 0;
                                                    }

                                                    // NameAndId 객체 추출
                                                    var targetProfile = profileOpt.get();

                                                    // 자기 자신 예외 처리 (targetProfile.id() 사용)
                                                    if (sourcePlayer.getUUID().equals(targetProfile.id())) {
                                                        source.sendFailure(Component.literal("자기 자신에게는 임시 권한을 부여할 수 없습니다."));
                                                        return 0;
                                                    }


                                                    // YAWP 구역: 플레이어가 위치한 구역 불러오기
                                                    ServerLevel level = (ServerLevel) sourcePlayer.level();
                                                    String dimPath = level.dimension().identifier().getPath();

                                                    final String effectiveWorld = switch (dimPath.toLowerCase()) {
                                                        case "overworld" -> "minecraft:overworld";
                                                        case "the_nether" -> "minecraft:the_nether";
                                                        case "the_end" -> "minecraft:the_end";
                                                        default -> dimPath; // 그 외의 경우(예: 커스텀 월드)는 입력값 그대로 사용
                                                    };

                                                    BlockPos playerPos = sourcePlayer.blockPosition();
                                                    IMarkableRegion region;
                                                    region = null;
                                                    var apiOpt = RegionManager.get().getDimRegionApiByKey(effectiveWorld);
                                                    if (apiOpt.isPresent()) {
                                                        if (apiOpt.get().getAllLocalRegions() != null && !apiOpt.get().getAllLocalRegions().isEmpty()) {
                                                            try {
                                                                if (apiOpt.get().findResponsibleRegion(playerPos).isPresent()) {
                                                                    region = (IMarkableRegion) apiOpt.get().findResponsibleRegion(playerPos).get();
                                                                }
                                                            } catch (Exception ignored) {}
                                                        }
                                                    }

                                                    if (region == null) {
                                                        source.sendFailure(Component.literal("자신의 구역에 입장한 상태로 명령어를 입력해주세요."));
                                                        return 0;
                                                    }

                                                    PlayerContainer owners = region.getGroups().get("owners");
                                                    if (owners == null || !owners.getPlayers().containsKey(sourcePlayer.getUUID())) {
                                                        source.sendFailure(Component.literal("자신의 구역에 입장한 상태로 명령어를 입력해주세요."));
                                                        return 0;
                                                    }

                                                    PlayerContainer members = region.getGroups().get("members");
                                                    if (members != null && members.getPlayers().containsKey(targetProfile.id())) {
                                                        if (!TempInviteManager.isTempMember(region.getName(), targetProfile.id())) {
                                                            source.sendFailure(Component.literal("이미 " + targetName + "님은 그 영역의 멤버 입니다."));
                                                            return 0;
                                                        }
                                                    }


                                                    // 블루맵 구역: 레이블 가져오기
                                                    String areaName;
                                                    var blueMapOpt = BlueMapAPI.getInstance();
                                                    if (blueMapOpt.isEmpty()) areaName = region.getName();
                                                    else {
                                                        BlueMapAPI api = blueMapOpt.get();
                                                        Map<String, MarkerSet> blueRegionMarkerSet = null;

                                                        for (var blueMapMap : api.getMaps()) {
                                                            String mapId = blueMapMap.getId();

                                                            boolean isMatch = mapId.equalsIgnoreCase(dimPath) ||
                                                                    (dimPath.equals("overworld") && mapId.equalsIgnoreCase("world")) ||
                                                                    mapId.toLowerCase().endsWith("_" + dimPath.toLowerCase());

                                                            if (isMatch) {
                                                                blueRegionMarkerSet = blueMapMap.getMarkerSets();
                                                                break;
                                                            }
                                                        }

                                                        if (blueRegionMarkerSet == null) {
                                                            areaName = region.getName();
                                                        } else {
                                                            ExtrudeMarker blueMarker = null;
                                                            for (MarkerSet markerSet : blueRegionMarkerSet.values()) {
                                                                if (markerSet.getMarkers().get(region.getName()) != null) {
                                                                    var marker = markerSet.getMarkers().get(region.getName());
                                                                    if (marker != null) {
                                                                        blueMarker = (ExtrudeMarker) marker;
                                                                        break;
                                                                    }
                                                                }
                                                            }

                                                            if (blueMarker != null) {
                                                                areaName = blueMarker.getLabel();
                                                            } else {
                                                                areaName = region.getName();
                                                            }
                                                        }
                                                    }



                                                    // NameAndId에서 id() 및 name() 필드 추출하여 초대 저장
                                                    TempInviteManager.addInvite(
                                                            source.getServer(),
                                                            targetProfile.id(),
                                                            targetProfile.name(),
                                                            region.getName(),
                                                            minutes
                                                    );

                                                    // 메시지 출력
                                                    source.sendSuccess(() -> Component.literal(
                                                            targetProfile.name() + "님에게 " + minutes + "분간 " + areaName + " 구역의 접근 권한을 부여했습니다."
                                                    ), false);

                                                    // 대상이 현재 온라인 접속 중이라면 실시간 메시지 전달
                                                    ServerPlayer onlineTarget = source.getServer().getPlayerList().getPlayer(targetProfile.id());
                                                    if (onlineTarget != null) {
                                                        onlineTarget.sendSystemMessage(Component.literal(
                                                                sourcePlayer.getScoreboardName() + "님의 " + areaName + " 구역 접근 권한을 " + minutes + "분간 부여받았습니다."
                                                        ));
                                                    }

                                                    return 1;
                                                })
                                        )
                                )
                        )
                        .then(Commands.literal("플래그")
                                .then(Commands.literal("추가")
                                        .then(Commands.argument("flagName", StringArgumentType.word())
                                                .suggests(TempCommand::getFlagSuggestions)
                                                .then(Commands.argument("allowed", BoolArgumentType.bool())
                                                    .suggests(TempCommand::getBoolSuggestions)
                                                    .executes(ctx -> {
                                                        CommandSourceStack source = ctx.getSource();
                                                        String flagName = StringArgumentType.getString(ctx, "flagName");
                                                        Boolean allowed = BoolArgumentType.getBool(ctx, "allowed");
                                                        String flagId = YAWP_FLAG_IDS.stream()
                                                                .filter(flag -> flag.equals(flagName))
                                                                .findFirst()
                                                                .orElse(null);

                                                        if (flagId != null) {
                                                            ServerPlayer sourcePlayer = source.getPlayerOrException();
                                                            ServerLevel level = sourcePlayer.level();
                                                            String dimPath = level.dimension().identifier().getPath();

                                                            final String effectiveWorld = switch (dimPath.toLowerCase()) {
                                                                case "overworld" -> "minecraft:overworld";
                                                                case "the_nether" -> "minecraft:the_nether";
                                                                case "the_end" -> "minecraft:the_end";
                                                                default -> dimPath; // 그 외의 경우(예: 커스텀 월드)는 입력값 그대로 사용
                                                            };

                                                            BlockPos playerPos = sourcePlayer.blockPosition();
                                                            IMarkableRegion region;
                                                            region = null;
                                                            var apiOpt = RegionManager.get().getDimRegionApiByKey(effectiveWorld);
                                                            if (apiOpt.isPresent()) {
                                                                if (apiOpt.get().getAllLocalRegions() != null && !apiOpt.get().getAllLocalRegions().isEmpty()) {
                                                                    try {
                                                                        if (apiOpt.get().findResponsibleRegion(playerPos).isPresent()) {
                                                                            region = (IMarkableRegion) apiOpt.get().findResponsibleRegion(playerPos).get();
                                                                        }
                                                                    } catch (Exception ignored) {}
                                                                }
                                                            }

                                                            if (region == null) {
                                                                source.sendFailure(Component.literal("자신의 구역에 입장한 상태로 명령어를 입력해주세요."));
                                                                return 0;
                                                            }

                                                            PlayerContainer owners = region.getGroups().get("owners");
                                                            if (owners == null || !owners.getPlayers().containsKey(sourcePlayer.getUUID())) {
                                                                source.sendFailure(Component.literal("자신의 구역에 입장한 상태로 명령어를 입력해주세요."));
                                                                return 0;
                                                            }

                                                            region.removeFlag(flagName);
                                                            try {
                                                                RegionFlag rf = RegionFlag.fromId(flagId);
                                                                FlagState state = allowed ? FlagState.ALLOWED : FlagState.DENIED;
                                                                region.getFlags().put(new BooleanFlag(rf, state, true));
                                                            } catch (IllegalArgumentException e) {
                                                                System.out.println("[Yawp] 알 수 없는 플래그 무시됨: " + flagId);
                                                            }

                                                            RegionManager.get().saveAll();

                                                            if (allowed) {
                                                                source.sendSuccess(() -> Component.literal(
                                                                        flagName + " 플래그가 '허용'으로 추가되었습니다."
                                                                ), false);
                                                            } else {
                                                                source.sendSuccess(() -> Component.literal(
                                                                        flagName + " 플래그가 '금지'로 추가되었습니다."
                                                                ), false);
                                                            }

                                                            return 1;
                                                        }
                                                        source.sendFailure(Component.literal("존재하지 않는 플래그입니다."));
                                                        return 0;
                                                    })
                                                )
                                        )
                                )
                                .then(Commands.literal("제거")
                                        .then(Commands.argument("flagName", StringArgumentType.word())
                                                .suggests(TempCommand::getFlagSuggestions)
                                                .executes(ctx -> {
                                                    CommandSourceStack source = ctx.getSource();
                                                    String flagName = StringArgumentType.getString(ctx, "flagName");
                                                    String flagId = YAWP_FLAG_IDS.stream()
                                                            .filter(flag -> flag.equals(flagName))
                                                            .findFirst()
                                                            .orElse(null);

                                                    if (flagId != null) {
                                                        ServerPlayer sourcePlayer = source.getPlayerOrException();
                                                        ServerLevel level = sourcePlayer.level();
                                                        String dimPath = level.dimension().identifier().getPath();

                                                        final String effectiveWorld = switch (dimPath.toLowerCase()) {
                                                            case "overworld" -> "minecraft:overworld";
                                                            case "the_nether" -> "minecraft:the_nether";
                                                            case "the_end" -> "minecraft:the_end";
                                                            default -> dimPath; // 그 외의 경우(예: 커스텀 월드)는 입력값 그대로 사용
                                                        };

                                                        BlockPos playerPos = sourcePlayer.blockPosition();
                                                        IMarkableRegion region;
                                                        region = null;
                                                        var apiOpt = RegionManager.get().getDimRegionApiByKey(effectiveWorld);
                                                        if (apiOpt.isPresent()) {
                                                            if (apiOpt.get().getAllLocalRegions() != null && !apiOpt.get().getAllLocalRegions().isEmpty()) {
                                                                try {
                                                                    if (apiOpt.get().findResponsibleRegion(playerPos).isPresent()) {
                                                                        region = (IMarkableRegion) apiOpt.get().findResponsibleRegion(playerPos).get();
                                                                    }
                                                                } catch (Exception ignored) {}
                                                            }
                                                        }

                                                        if (region == null) {
                                                            source.sendFailure(Component.literal("자신의 구역에 입장한 상태로 명령어를 입력해주세요."));
                                                            return 0;
                                                        }

                                                        PlayerContainer owners = region.getGroups().get("owners");
                                                        if (owners == null || !owners.getPlayers().containsKey(sourcePlayer.getUUID())) {
                                                            source.sendFailure(Component.literal("자신의 구역에 입장한 상태로 명령어를 입력해주세요."));
                                                            return 0;
                                                        }

                                                        region.removeFlag(flagName);

                                                        RegionManager.get().saveAll();

                                                        source.sendSuccess(() -> Component.literal(
                                                                flagName + " 플래그가 제거되었습니다."
                                                        ), false);

                                                        return 1;
                                                    }
                                                    source.sendFailure(Component.literal("존재하지 않는 플래그입니다."));
                                                    return 0;
                                                })
                                        )
                                )
                        )
        );
    }

    private static CompletableFuture<Suggestions> getFlagSuggestions(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(YAWP_FLAG_IDS, builder);
    }

    private static CompletableFuture<Suggestions> getBoolSuggestions(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder) {
        final List<String> list_bool = List.of("true", "false");

        return SharedSuggestionProvider.suggest(list_bool, builder);
    }
}
