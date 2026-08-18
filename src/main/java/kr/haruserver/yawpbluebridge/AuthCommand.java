package kr.haruserver.yawpbluebridge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import javax.net.ssl.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;

public class AuthCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("인증")
                .then(Commands.argument("code", StringArgumentType.string())
                        .executes(context -> {
                            String code = StringArgumentType.getString(context, "code");
                            ServerPlayer player = context.getSource().getPlayer();

                            if (player == null) return 0;

                            // 비동기 처리
                            CompletableFuture.runAsync(() -> {
                                try {
                                    String uuid = player.getStringUUID();
                                    String urlStr = "https://127.0.0.1/auth/minecraft/confirm?code=" + code + "&uuid=" + uuid;
                                    URL url = new URL(urlStr);

                                    // 1. 모든 인증서를 신뢰하는 TrustManager 설정
                                    TrustManager[] trustAllCerts = new TrustManager[]{
                                            new X509TrustManager() {
                                                public X509Certificate[] getAcceptedIssuers() { return null; }
                                                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                                                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                                            }
                                    };

                                    // 2. SSLContext 초기화
                                    SSLContext sc = SSLContext.getInstance("TLS");
                                    sc.init(null, trustAllCerts, new SecureRandom());

                                    // 3. 🚨 호스트네임 검증기 무시 설정 (127.0.0.1 에러 해결의 핵심)
                                    HostnameVerifier allHostsValid = (hostname, session) -> true;

                                    // 4. 연결 설정
                                    HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                                    conn.setSSLSocketFactory(sc.getSocketFactory());
                                    conn.setHostnameVerifier(allHostsValid); // 👈 검증 로직을 "무조건 참"으로 교체

                                    conn.setRequestMethod("POST");
                                    conn.setConnectTimeout(5000);
                                    conn.setReadTimeout(5000);
                                    conn.setDoOutput(false); // 바디가 없는 POST이므로 false

                                    int responseCode = conn.getResponseCode();

                                    if (responseCode == 200) {
                                        player.sendSystemMessage(Component.literal("§a[HaruAuth] 인증에 성공했습니다!"), false);
                                    } else {
                                        player.sendSystemMessage(Component.literal("§c[HaruAuth] 인증 실패 (상태 코드: " + responseCode + ")"), false);
                                    }

                                    conn.disconnect();
                                } catch (Exception e) {
                                    player.sendSystemMessage(Component.literal("§c[HaruAuth] 인증 서버 연결 실패. 로그를 확인하세요."), false);
                                    e.printStackTrace();
                                }
                            });

                            return 1;
                        })));
    }
}