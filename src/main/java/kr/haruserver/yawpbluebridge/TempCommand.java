package kr.haruserver.yawpbluebridge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import javax.net.ssl.*;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class TempCommand {

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

                                                    // NameAndId에서 id() 및 name() 필드 추출하여 초대 저장
                                                    TempInviteManager.addInvite(
                                                            source.getServer(),
                                                            targetProfile.id(),
                                                            targetProfile.name(),
                                                            sourcePlayer.getUUID(),
                                                            minutes
                                                    );

                                                    // 메시지 출력
                                                    source.sendSuccess(() -> Component.literal(
                                                            targetProfile.name() + "님에게 " + minutes + "분간 내 모든 구역의 접근 권한을 부여했습니다."
                                                    ), false);

                                                    // 대상이 현재 온라인 접속 중이라면 실시간 메시지 전달
                                                    ServerPlayer onlineTarget = source.getServer().getPlayerList().getPlayer(targetProfile.id());
                                                    if (onlineTarget != null) {
                                                        onlineTarget.sendSystemMessage(Component.literal(
                                                                sourcePlayer.getScoreboardName() + "님의 구역 접근 권한을 " + minutes + "분간 부여받았습니다."
                                                        ));
                                                    }

                                                    return 1;
                                                })
                                        )
                                )
                        )
        );
    }
}
