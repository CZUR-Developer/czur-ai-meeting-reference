#!/usr/bin/env node

import http from 'node:http';
import https from 'node:https';
import { readFileSync } from 'node:fs';

/**
 * 本机局域网 HTTPS 调试入口，只终止 TLS 并流式转发到指定参考 HTTP 后端。
 *
 * 证书／私钥由仓外文件提供，不申请证书、不存储凭据、不解析请求正文。
 * 登录、会议控制以及 Artifact 上传／下载共用同一通道；录音文件按流传输，
 * 不在内存中累计完整文件。模型音频和模型 WebSocket 仍由 Android SDK 直连。
 * 本工具只用于开发联调，正式部署可使用部署方已有的 HTTPS 反向代理。
 */
function main() {
  const certificateFile = process.env.REFERENCE_TLS_CERT;
  const privateKeyFile = process.env.REFERENCE_TLS_KEY;
  if (!certificateFile || !privateKeyFile) {
    throw new Error('请通过 REFERENCE_TLS_CERT／REFERENCE_TLS_KEY 指定仓外证书与私钥文件');
  }
  const bind = process.env.REFERENCE_TLS_BIND || '127.0.0.1';
  // 端口 0 供本机定向检查分配空闲端口；正常联调使用固定端口。
  const port = Number(process.env.REFERENCE_TLS_PORT || '8443');
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    throw new Error('REFERENCE_TLS_PORT 必须是 0～65535 的整数');
  }
  const upstream = new URL(process.env.REFERENCE_TLS_UPSTREAM || 'http://127.0.0.1:8080');
  if (upstream.protocol !== 'http:' || upstream.username || upstream.password
      || upstream.pathname !== '/' || upstream.search || upstream.hash) {
    throw new Error('REFERENCE_TLS_UPSTREAM 须为无凭据、无路径的固定 HTTP 后端地址');
  }

  const server = https.createServer({
    cert: readFileSync(certificateFile),
    key: readFileSync(privateKeyFile),
  }, (request, response) => {
    const upstreamRequest = http.request({
      hostname: upstream.hostname,
      port: upstream.port || 80,
      method: request.method,
      path: request.url,
      headers: { ...endToEndHeaders(request.headers), host: upstream.host },
    }, upstreamResponse => {
      response.writeHead(upstreamResponse.statusCode, endToEndHeaders(upstreamResponse.headers));
      upstreamResponse.on('error', () => response.destroy());
      // pipe 保留流式背压；Content-Length、Range 和 Content-Range 原样经过。
      upstreamResponse.pipe(response);
    });
    upstreamRequest.on('error', () => {
      console.error('[ERROR] 本机 HTTPS 入口连接参考 HTTP 后端失败');
      if (response.headersSent) response.destroy();
      else {
        response.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
        response.end('参考 HTTP 后端暂不可用');
      }
    });
    request.on('error', () => upstreamRequest.destroy());
    request.on('aborted', () => upstreamRequest.destroy());
    response.on('close', () => {
      if (!response.writableFinished) upstreamRequest.destroy();
    });
    request.pipe(upstreamRequest);
  });

  // 本机入口只承载 HTTP。模型 WSS 连接不得误接入此处。
  server.on('upgrade', (_request, socket) => {
    socket.end('HTTP/1.1 501 Not Implemented\r\nConnection: close\r\n\r\n');
  });
  server.on('error', () => {
    console.error('[ERROR] 本机 HTTPS 入口启动失败，请检查绑定地址和端口');
    process.exitCode = 1;
  });
  server.listen(port, bind, () => {
    console.log(`[INFO] 本机 HTTPS 调试入口已启动 port=${server.address().port}`);
  });
}

/** 逐跳连接头由两端各自管理，避免将上游连接的分块／关闭语义带到另一条连接。 */
function endToEndHeaders(headers) {
  const forwarded = { ...headers };
  const connectionHeaders = String(headers.connection || '').split(',').map(name => name.trim().toLowerCase());
  for (const name of [...connectionHeaders, 'connection', 'keep-alive', 'proxy-authenticate',
    'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade']) {
    delete forwarded[name];
  }
  return forwarded;
}

try {
  main();
} catch {
  // 文件和 TLS 异常可能带有仓外路径；日志只保留排查方向，不展开配置内容。
  console.error('[ERROR] 无法启动本机 HTTPS 入口，请检查 TLS 环境变量、证书和私钥');
  process.exitCode = 1;
}
