# 本机局域网 HTTPS 联调

本页用于 Android 设备通过开发电脑的真实局域网地址连接参考后端。参考 SDK 的登录
地址继续要求 HTTPS；`adb reverse` 的 loopback 例外只用于诊断，不能代替局域网验收。
这里使用 Node.js 标准库提供临时 TLS 入口，不修改 SDK，也不依赖第三方 npm 包。

流向：`设备 HTTPS → 本机 TLS 入口 → 本机参考 HTTP 后端`。登录、会议控制和
Artifact 文件上传／下载经过入口；原始请求与响应按流转发，支持现有文件头和 Range。
SDK 的实时模型音频／WSS 仍直连所配置的模型，本工具不提供 WSS 代理。

## 1. 在仓外生成本机 CA 和 IP SAN 证书

以下命令使用 OpenSSL 1.1.1 或更新版本。把 `PC_LAN_IP` 改成设备能访问的开发电脑
局域网 IP；证书的 IP Subject Alternative Name（SAN，主体备用名称）必须与 App
构建时注入的 IP 一致。证书、私钥和本机实际地址都不提交到仓库。

```bash
# 选择仓库以外的本机目录；示例使用随机临时目录。
REFERENCE_TLS_DIR="$(mktemp -d -t reference-lan-tls.XXXXXX)"
PC_LAN_IP='192.0.2.10' # 文档占位地址，执行前替换。
umask 077

openssl req -x509 -newkey rsa:2048 -nodes -days 30 \
  -config <(printf '[req]\ndistinguished_name=dn\n[dn]\n') \
  -subj '/CN=Reference Local Development CA' \
  -addext 'basicConstraints=critical,CA:TRUE' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign' \
  -keyout "$REFERENCE_TLS_DIR/ca.key" -out "$REFERENCE_TLS_DIR/ca.pem"

openssl req -new -newkey rsa:2048 -nodes \
  -config <(printf '[req]\ndistinguished_name=dn\n[dn]\n') \
  -subj '/CN=Reference LAN Development' \
  -addext "subjectAltName=IP:$PC_LAN_IP" \
  -addext 'basicConstraints=critical,CA:FALSE' \
  -addext 'keyUsage=critical,digitalSignature,keyEncipherment' \
  -addext 'extendedKeyUsage=serverAuth' \
  -keyout "$REFERENCE_TLS_DIR/server.key" -out "$REFERENCE_TLS_DIR/server.csr"

# OpenSSL 1.1.1 的 x509 不自动复制 CSR 扩展，签发时显式传入相同的 SAN 等配置。
openssl x509 -req -days 30 -sha256 \
  -in "$REFERENCE_TLS_DIR/server.csr" \
  -CA "$REFERENCE_TLS_DIR/ca.pem" -CAkey "$REFERENCE_TLS_DIR/ca.key" -CAcreateserial \
  -extfile <(printf 'subjectAltName=IP:%s\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' "$PC_LAN_IP") \
  -out "$REFERENCE_TLS_DIR/server.pem"

openssl verify -CAfile "$REFERENCE_TLS_DIR/ca.pem" "$REFERENCE_TLS_DIR/ca.pem"
openssl verify -CAfile "$REFERENCE_TLS_DIR/ca.pem" \
  -purpose sslserver -verify_ip "$PC_LAN_IP" "$REFERENCE_TLS_DIR/server.pem"
```

两条 `req` 命令显式使用同一份最小配置，只通过 `-addext` 设置扩展，不继承系统
`openssl.cnf` 中的默认扩展，避免某些 OpenSSL 1.1.1 环境生成重复 `basicConstraints`。
CA 私钥只用于本机签证；代理只读取 `server.key`。Debug APK 只打包公开的 `ca.pem`，
不打包任意私钥。更换 IP 后应重新生成含新 IP SAN 的服务端证书。

## 2. 启动已有 HTTP 后端和临时 HTTPS 入口

先按[接入手册](partner-integration-guide.md)初始化 MySQL，并启动参考 HTTP 后端。
使用交付容器时，先按[部署说明](../deploy/README.md)加载 `.env`、执行唯一 V001，
再运行 Compose；下面的上游仍填写宿主机映射端口（默认 `8080`），不填写应用容器
内部地址，也不把这个 HTTP 上游作为 Android 登录地址。
本工具不会创建数据库，也不会修改、重启已有后端。另开终端，在参考仓运行：

```bash
REFERENCE_TLS_CERT="$REFERENCE_TLS_DIR/server.pem" \
REFERENCE_TLS_KEY="$REFERENCE_TLS_DIR/server.key" \
REFERENCE_TLS_BIND="$PC_LAN_IP" \
REFERENCE_TLS_PORT=8443 \
REFERENCE_TLS_UPSTREAM='http://127.0.0.1:8080' \
node tools/run-reference-lan-https.mjs
```

如果换了终端，先恢复前一步的 `REFERENCE_TLS_DIR` 和 `PC_LAN_IP` 变量。默认仅绑定
`127.0.0.1`；真实局域网联调应按上述参数绑定 PC 的局域网接口，也可显式绑定
`0.0.0.0`。上游地址须指向实际 HTTP 监听端口。日志只报告启动端口和固定错误，
不会展开请求正文、认证头或仓外文件内容。

可在 PC 使用相同 CA 做一次正常读取：

```bash
curl --cacert "$REFERENCE_TLS_DIR/ca.pem" "https://$PC_LAN_IP:8443/v1/capabilities"
```

不使用 `curl -k` 或绕过证书校验。生产部署使用部署方自己的 HTTPS 域名／证书和
正式反向代理；本脚本不属于必须交付的产品镜像。

## 3. 构建（代码即配置）

参考 App 的地址和调试 CA 已经在代码/资源里：

- 后端/结果页：`android-reference-app/app/src/main/java/com/czur/aimeeting/reference/meeting/ReferenceDeployment.kt`
- 调试 CA：`android-reference-app/app/src/debug/res/raw/reference_debug_ca.pem`

构建不需要任何 `-P` 参数：

```bash
cd android-reference-app
./gradlew :app:assembleDebug
```

设备 AAR 随仓在 `meeting-android/libs/ai-meeting-device.aar`，不再需要命令行路径。
Debug 构建自动信任上面的开发 CA；Release 不信任它，正式部署使用部署方自己的受信任
HTTPS 证书。运维提供正式 CA 后，替换调试 CA 文件或改走系统信任。页面不提供终端用户
填写地址的入口；配置来源与身份边界见[Android 说明](../android-reference-app/README.md)。

## 4. 安装后正常登录与 Web 配置

将 Debug APK 安装到与 PC 可互通的设备后，直接打开 App 并正常登录；无需输入后端或
结果页地址，设备工具也不再接收这些参数。实际连接只使用上述构建配置，已有手输值不
覆盖编译值。同一后端升级保留有效登录，更换后端重新构建后不复用原后端会话。
不要把设备自己的 `127.0.0.1` 当作 PC，真实局域网验收也不依赖 `adb reverse`。

Web 仍沿用已有静态 Web 启动脚本；`REFERENCE_HTTP_BASE_URL` 可使用 PC 的局域网
HTTP 后端地址，App 的 Web 结果构建地址指向设备可达的静态站点（例如
`http://<PC_LAN_IP>:8090/index.html`），与后端 API 地址分开配置。结果页无需与 Android 入口采用
相同协议。生产 HTTPS 页面应连接 HTTPS API，避免混合内容。若浏览器结果页也指向该
开发 HTTPS 入口，浏览器需要自行信任这份本机 CA；Android Debug 的信任不影响浏览器。

正常启动可用 [`meeting-device.py`](../tools/android-device/README.md) 的
`setup-start --username '<部署账号>'`，密码沿用环境注入；此时只执行
`open → 正常UI登录 → options → start`。诊断用 reverse 也需要先在 PC 上构建 loopback
地址的专用包并安装，再显式建立端口映射；没有运行时填写地址的步骤，不作为 LAN 验收。

正常验收记录应包含真实局域网地址连接、登录、能力发现、会议正常停止和同场 Artifact
结果；TLS 工具检查、APK 构建或 loopback 诊断不代签设备会议和真实模型验证。
