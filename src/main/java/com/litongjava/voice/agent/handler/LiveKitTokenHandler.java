package com.litongjava.voice.agent.handler;

import java.util.Map;

import com.litongjava.model.body.RespBodyVo;
import com.litongjava.tio.boot.http.TioRequestContext;
import com.litongjava.tio.http.common.HttpRequest;
import com.litongjava.tio.http.common.HttpResponse;
import com.litongjava.tio.http.server.handler.HttpRequestHandler;
import com.litongjava.tio.http.server.util.CORSUtils;
import com.litongjava.tio.utils.hutool.StrUtil;
import com.litongjava.voice.agent.utils.LiveKitTokenUtil;

public class LiveKitTokenHandler implements HttpRequestHandler {

  //GET /api/v1/livekit/token?room=test&identity=u1
  @Override
  public HttpResponse handle(HttpRequest httpRequest) throws Exception {
    HttpResponse response = TioRequestContext.getResponse();
    CORSUtils.enableCORS(response);

    String room = httpRequest.getParam("room");
    String identity = httpRequest.getParam("identity");

    String r = StrUtil.defaultIfBlank(room, "test").trim();
    String id = StrUtil.defaultIfBlank(identity, "u-" + System.currentTimeMillis()).trim();

    Map<String, Object> map = LiveKitTokenUtil.buildTokenResponse(r, id);
    response.body(RespBodyVo.ok(map));
    return response;
  }

}
