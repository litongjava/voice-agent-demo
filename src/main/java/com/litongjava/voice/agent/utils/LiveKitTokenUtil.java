package com.litongjava.voice.agent.utils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

public class LiveKitTokenUtil {

  // 你的配置
  public static final String LIVEKIT_URL = "ws://192.168.3.151:7880";
  public static final String API_KEY = "devkey";
  public static final String API_SECRET = "abcedfghijklmnopqrstuvwxyz1234567890";

  /**
   * 生成加入房间 token（给浏览器用：允许发布/订阅音频）
   */
  public static String buildJoinToken(String room, String identity, int ttlSeconds) {
    long now = Instant.now().getEpochSecond();
    long exp = now + Math.max(60, ttlSeconds);

    Map<String, Object> videoGrant = new HashMap<>();
    videoGrant.put("room", room);
    videoGrant.put("roomJoin", true);
    // 建议显式打开，避免默认行为差异
    videoGrant.put("canPublish", true);
    videoGrant.put("canSubscribe", true);

    Algorithm alg = Algorithm.HMAC256(API_SECRET);

    return JWT.create().withIssuer(API_KEY) // iss
        .withSubject(identity) // sub
        .withNotBefore(Instant.ofEpochSecond(now)).withExpiresAt(Instant.ofEpochSecond(exp))
        .withClaim("video", videoGrant).sign(alg);
  }

  /**
   * 返回给前端的 JSON
   */
  public static Map<String, Object> buildTokenResponse(String room, String identity) {
    String token = buildJoinToken(room, identity, 3600);
    Map<String, Object> resp = new HashMap<>();
    resp.put("url", LIVEKIT_URL);
    resp.put("token", token);
    resp.put("room", room);
    resp.put("identity", identity);
    return resp;
  }

  public static String buildSipAdminCallToken(String identity, int ttlSeconds) {
    long now = java.time.Instant.now().getEpochSecond();
    long exp = now + Math.max(60, ttlSeconds);

    java.util.Map<String, Object> sipGrant = new java.util.HashMap<>();
    sipGrant.put("admin", true);
    sipGrant.put("call", true);

    // video grant 不必须，但加上也没坏处（方便排查/复用）
    java.util.Map<String, Object> videoGrant = new java.util.HashMap<>();
    videoGrant.put("room", "test");
    videoGrant.put("roomJoin", true);

    Algorithm alg = Algorithm.HMAC256(API_SECRET);
    return com.auth0.jwt.JWT.create().withIssuer(API_KEY).withSubject(identity)
        .withNotBefore(java.time.Instant.ofEpochSecond(now)).withExpiresAt(java.time.Instant.ofEpochSecond(exp))
        .withClaim("sip", sipGrant).withClaim("video", videoGrant).sign(alg);
  }
}